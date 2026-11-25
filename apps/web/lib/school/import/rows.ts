import type { ImportKind, ImportRow, RowStatus } from "./state";

/** The row lists of the review step: "all" has no status filter. */
export const ROW_FILTERS = ["ALL", "INVALID", "DUPLICATE", "VALID", "SKIPPED"] as const;
export type RowFilter = (typeof ROW_FILTERS)[number];

export function statusParam(filter: RowFilter): RowStatus | undefined {
  return filter === "ALL" ? undefined : filter;
}

/** Which filter to open the review with: what needs the person's attention first. */
export function defaultFilter(counts: { invalid: number; duplicates: number } | undefined): RowFilter {
  if ((counts?.duplicates ?? 0) > 0) return "DUPLICATE";
  if ((counts?.invalid ?? 0) > 0) return "INVALID";
  return "ALL";
}

const text = (value: unknown): string => (typeof value === "string" ? value.trim() : "");

/**
 * A short name for a row, from what the API understood of it (never from the URL or storage). Falls back to the
 * first cell, then to the row number.
 */
export function rowLabel(row: Pick<ImportRow, "normalized" | "cells" | "rowNumber">, kind: ImportKind): string {
  const n = row.normalized ?? {};
  const joined = (...keys: string[]) => keys.map((k) => text(n[k])).filter(Boolean).join(" ");
  let label = "";
  switch (kind) {
    case "STUDENTS":
    case "STAFF":
      label = joined("givenName", "middleName", "familyName");
      break;
    case "CLASSES":
      label = text(n.className);
      break;
    case "ENROLLMENTS":
      label = [text(n.studentName), text(n.className)].filter(Boolean).join(" → ");
      break;
    case "GRADE_LEVELS":
    case "SUBJECTS":
      label = text(n.name);
      break;
  }
  if (label) return label;
  const first = row.cells?.find((c) => c.trim() !== "");
  return first?.trim() ?? "";
}

export interface Candidate {
  studentId: string;
  displayName: string;
  dateOfBirth: string;
  studentNumber: string;
  /** 0 to 1. */
  similarity: number;
  sameBirthDate: boolean;
  classes: string[];
}

/** The possible matches of a row, read defensively (the API sends loose maps). */
export function candidatesOf(row: Pick<ImportRow, "duplicateCandidates">): Candidate[] {
  const out: Candidate[] = [];
  for (const raw of row.duplicateCandidates ?? []) {
    const id = text(raw.studentId);
    if (!id) continue;
    out.push({
      studentId: id,
      displayName: text(raw.displayName),
      dateOfBirth: text(raw.dateOfBirth),
      studentNumber: text(raw.studentNumber),
      similarity: typeof raw.similarity === "number" ? raw.similarity : 0,
      sameBirthDate: raw.birthDate === "SAME",
      classes: Array.isArray(raw.classes) ? raw.classes.filter((c): c is string => typeof c === "string") : [],
    });
  }
  return out;
}

export type Resolution = "LINK" | "CREATE_NEW" | "SKIP";

/** The decision a row already carries ("LINK" and so on), when it is one the wizard knows. */
export function resolutionOf(row: Pick<ImportRow, "resolution">): Resolution | null {
  return row.resolution === "LINK" || row.resolution === "CREATE_NEW" || row.resolution === "SKIP" ? row.resolution : null;
}

/** A possible duplicate still waits for a decision. */
export function isOpenDuplicate(row: Pick<ImportRow, "status" | "resolution">): boolean {
  return row.status === "DUPLICATE" && resolutionOf(row) === null;
}

/** Dedupes rows by id when pages overlap after a row was changed (the lists are keyset-paged by row number). */
export function mergeRows(pages: readonly (readonly ImportRow[])[]): ImportRow[] {
  const seen = new Set<string>();
  const out: ImportRow[] = [];
  for (const page of pages) {
    for (const row of page) {
      if (seen.has(row.id)) continue;
      seen.add(row.id);
      out.push(row);
    }
  }
  return out;
}
