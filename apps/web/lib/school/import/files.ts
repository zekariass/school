import { validateFile, type FileProblem, type PurposePolicy } from "@/lib/media/policies";

export const CSV_MIME = "text/csv";
export const XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export type ImportFileProblem = FileProblem | { code: "extension" };

/**
 * The type to upload a roster file as. Browsers disagree about CSV (an empty type, `text/plain`, or the Excel
 * type on Windows), and the API checks the name against the type: `.csv` is sent as text/csv and `.xlsx` as a
 * workbook, whatever the browser said. Anything else (`.xls`, `.xlsm`, ...) is null: it cannot be imported.
 */
export function importMimeType(filename: string): string | null {
  const name = filename.trim().toLowerCase();
  if (name.endsWith(".csv")) return CSV_MIME;
  if (name.endsWith(".xlsx")) return XLSX_MIME;
  return null;
}

/** Checks a chosen file with the rules from `GET /media/policies` (the API still decides). Null means it may go up. */
export function checkImportFile(file: { name: string; size: number }, policy: PurposePolicy): ImportFileProblem | null {
  const type = importMimeType(file.name);
  if (!type) return { code: "extension" };
  return validateFile({ type, size: file.size }, policy);
}

/** The same bytes with the type the API expects (a copy only when the browser's type differs). */
export function asImportFile(file: File): File {
  const type = importMimeType(file.name);
  if (!type || file.type === type) return file;
  return new File([file], file.name, { type, lastModified: file.lastModified });
}

/** "students.csv" to "students-errors.csv"; the name never carries a path. */
export function errorReportName(filename: string): string {
  const base = filename.replace(/^.*[\\/]/, "").replace(/\.[^.]+$/, "").trim() || "import";
  return `${base}-errors.csv`;
}

/** The template's file name for a type and format, as the API names it. */
export function templateName(importType: string, format: "csv" | "xlsx"): string {
  return `${importType.toLowerCase().replace(/_/g, "-")}-template.${format}`;
}
