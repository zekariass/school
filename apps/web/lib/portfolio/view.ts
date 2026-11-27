import type { PortfolioItem, PortfolioStatus, PortfolioVisibility } from "./queries";

/** Order of the audiences, narrowest first (the same order as the API's `PortfolioVisibility`). */
export const VISIBILITIES: readonly PortfolioVisibility[] = ["STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL"];

export const STATUSES: readonly PortfolioStatus[] = ["DRAFT", "SUBMITTED", "CHANGES_REQUESTED", "APPROVED", "REJECTED", "ARCHIVED"];

export const visibilityRank = (visibility: PortfolioVisibility): number => VISIBILITIES.indexOf(visibility);

/**
 * The audiences a student can pick: up to the school's cap (`capabilities.maxVisibility`). A wider choice would be
 * lowered by the API anyway (the stored choice is never rewritten), so the form does not offer it.
 */
export function visibilityChoices(maxVisibility: PortfolioVisibility | undefined): PortfolioVisibility[] {
  const limit = maxVisibility ? visibilityRank(maxVisibility) : visibilityRank("GUARDIANS");
  return VISIBILITIES.filter((v) => visibilityRank(v) <= limit);
}

/** The audiences a reviewer may approve with: the item's own or narrower (the API refuses a wider one). */
export function reviewVisibilityChoices(visibility: PortfolioVisibility): PortfolioVisibility[] {
  return VISIBILITIES.filter((v) => visibilityRank(v) <= visibilityRank(visibility));
}

/** True when the school's cap shows the item to a narrower audience than the one chosen. */
export function isCapped(item: Pick<PortfolioItem, "visibility" | "effectiveVisibility">): boolean {
  return item.visibility !== item.effectiveVisibility;
}

export type StatusTone = "neutral" | "pending" | "attention" | "good" | "bad";

/** The look of a status badge. Words always come with it (colour is never the only signal). */
export function statusTone(status: PortfolioStatus): StatusTone {
  switch (status) {
    case "DRAFT":
    case "ARCHIVED":
      return "neutral";
    case "SUBMITTED":
      return "pending";
    case "CHANGES_REQUESTED":
      return "attention";
    case "APPROVED":
      return "good";
    case "REJECTED":
      return "bad";
  }
}

/**
 * Whether the item's history may be read. The API sends `taggedStudents` (like `reviewNote`) only to the student and
 * the item's reviewers, which are exactly the people who may read the history; everybody else would get 403.
 */
export function mayReadHistory(item: Pick<PortfolioItem, "taggedStudents">): boolean {
  return Array.isArray(item.taggedStudents);
}

/** The files of the item that are images, in order. */
export function imageFiles<T extends { kind: string }>(media: readonly T[]): T[] {
  return media.filter((m) => m.kind === "IMAGE");
}

/** The files that are not images (PDF documents). */
export function documentFiles<T extends { kind: string }>(media: readonly T[]): T[] {
  return media.filter((m) => m.kind !== "IMAGE");
}

/** The first image of an item, for a list card; undefined for text items and withheld files. */
export function coverFile<T extends { kind: string }>(media: readonly T[]): T | undefined {
  return media.find((m) => m.kind === "IMAGE");
}

/** The history actions the API can record (each has a label in the catalog). */
export const HISTORY_ACTIONS = ["CAPTURED", "SUBMITTED", "APPROVED", "CHANGES_REQUESTED", "REJECTED", "ARCHIVED", "UNARCHIVED", "FEATURED", "UNFEATURED", "DELETED"] as const;

export type HistoryAction = (typeof HISTORY_ACTIONS)[number];

export const isHistoryAction = (action: string): action is HistoryAction => (HISTORY_ACTIONS as readonly string[]).includes(action);
