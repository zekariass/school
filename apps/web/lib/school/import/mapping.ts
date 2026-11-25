import type { ImportField } from "./state";

/** Field key to the zero-based index of the file column that fills it. A field that is not there is not mapped. */
export type Mapping = Record<string, number>;

/** "A", "B", ... "Z", "AA": the column's name in a spreadsheet. */
export function columnLetter(index: number): string {
  let n = Math.max(0, Math.floor(index));
  let letters = "";
  do {
    letters = String.fromCharCode(65 + (n % 26)) + letters;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return letters;
}

/** Maps a field to a column (or clears it with null). A column can fill one field only, so it is taken from the field that had it. */
export function setColumn(mapping: Mapping, field: string, column: number | null): Mapping {
  const next: Mapping = {};
  for (const [key, index] of Object.entries(mapping)) {
    if (key === field) continue;
    if (column !== null && index === column) continue;
    next[key] = index;
  }
  if (column !== null) next[field] = column;
  return next;
}

/** The mapping the API suggests, without entries that point outside the columns. */
export function cleanMapping(mapping: Mapping | undefined, columnCount: number): Mapping {
  const out: Mapping = {};
  for (const [key, index] of Object.entries(mapping ?? {})) {
    if (Number.isInteger(index) && index >= 0 && index < columnCount) out[key] = index;
  }
  return out;
}

/**
 * What is still missing. `requiredFields` entries are a field key, or "a|b" where one of the fields is enough;
 * a missing group is reported as the whole entry.
 */
export function missingFields(requiredFields: readonly string[], mapping: Mapping): string[] {
  return requiredFields.filter((entry) => !entry.split("|").some((key) => key in mapping));
}

/** Whether the field must be mapped by itself, belongs to an "one of" group, or is optional. */
export function requirementOf(field: ImportField, requiredFields: readonly string[]): "required" | "oneOf" | "optional" {
  if (field.required) return "required";
  if (requiredFields.some((entry) => entry.includes("|") && entry.split("|").includes(field.key))) return "oneOf";
  return "optional";
}

/** Compares header texts the way people write them: case, spaces and Unicode form do not matter. */
export function headerKey(text: string): string {
  return text.normalize("NFC").trim().replace(/\s+/g, " ").toLocaleLowerCase();
}

/**
 * Applies a saved profile (field key to header text) to this file's headers. Returns the mapping and the fields
 * whose header is not in the file (they stay unmapped for the person to choose).
 */
export function applyProfile(
  profile: Record<string, string>,
  headers: readonly string[],
  base: Mapping = {},
): { mapping: Mapping; unmatched: string[] } {
  let mapping: Mapping = { ...base };
  const unmatched: string[] = [];
  const byHeader = new Map<string, number>();
  headers.forEach((header, index) => {
    const key = headerKey(header);
    if (key && !byHeader.has(key)) byHeader.set(key, index);
  });
  for (const [field, header] of Object.entries(profile)) {
    const index = byHeader.get(headerKey(header));
    if (index === undefined) unmatched.push(field);
    else mapping = setColumn(mapping, field, index);
  }
  return { mapping, unmatched };
}

/** The request body's mapping: only mapped fields. */
export function mappingPayload(mapping: Mapping): Record<string, number> {
  return { ...mapping };
}

/** Up to `limit` non-empty example values of a column, from the preview rows (rows marked as examples are left out). */
export function sampleValues(
  rows: readonly { cells: readonly string[]; example: boolean }[],
  column: number,
  limit = 2,
): string[] {
  const out: string[] = [];
  for (const row of rows) {
    if (row.example) continue;
    const value = row.cells[column]?.trim();
    if (value) out.push(value);
    if (out.length >= limit) break;
  }
  return out;
}

/** True when two fields point to the same column (the API refuses it; the editor never produces it). */
export function hasReusedColumn(mapping: Mapping): boolean {
  const indexes = Object.values(mapping);
  return new Set(indexes).size !== indexes.length;
}

// --- reading options -------------------------------------------------------------------------

export const ENCODINGS = ["UTF-8", "UTF-16LE", "UTF-16BE", "windows-1252"] as const;
/** The delimiters a person can choose; the API wants a single character, or "tab". */
export const DELIMITERS = [
  { id: "comma", value: ",", display: "," },
  { id: "semicolon", value: ";", display: ";" },
  { id: "tab", value: "tab", display: "\t" },
  { id: "pipe", value: "|", display: "|" },
] as const;
export type DelimiterId = (typeof DELIMITERS)[number]["id"];

/** The option id of a delimiter character reported by the API ("," or a real tab), or "" when unknown. */
export function delimiterIdOf(detected: string | undefined | null): DelimiterId | "" {
  if (!detected) return "";
  const hit = DELIMITERS.find((d) => d.display === detected || d.value === detected || (d.id === "tab" && detected === "\\t"));
  return hit?.id ?? "";
}

/** The value the API wants for a delimiter option id ("" means detect). */
export function delimiterValue(id: DelimiterId | ""): string {
  return DELIMITERS.find((d) => d.id === id)?.value ?? "";
}

/** The encoding label the API reports ("UTF-8") as one of the options, or "" when not one of them. */
export function encodingIdOf(detected: string | undefined | null): string {
  return ENCODINGS.find((e) => e.toLowerCase() === detected?.toLowerCase()) ?? "";
}

export interface ReadingChoice {
  /** "" means detect. */
  encoding: string;
  delimiter: DelimiterId | "";
  sheet: string;
}

/** Query parameters for the re-read preview; all three are sent (empty = detect) once the person touched one of them. */
export function readingQuery(choice: ReadingChoice | null): { encoding: string; delimiter: string; sheet: string } | null {
  if (!choice) return null;
  return { encoding: choice.encoding, delimiter: delimiterValue(choice.delimiter), sheet: choice.sheet };
}
