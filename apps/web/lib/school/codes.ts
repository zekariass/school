/**
 * Login-code sheets. The plaintext codes exist only in the response of the issue call
 * and in the memory of the page that asked for them (see `IssuedCodesProvider`): never
 * in a URL, storage or a query cache. These helpers are the pure parts.
 */

export type CodeKind = "QR" | "TEXT" | "BOTH";

export interface CodeEntry {
  studentId: string;
  displayName: string;
  expiresAt: string;
  qrToken?: string | null;
  textCode?: string | null;
}

/** What one "generate codes" call produced. */
export interface CodeSheet {
  classId: string;
  className: string;
  kind: CodeKind;
  issuedAt: number;
  codes: CodeEntry[];
}

/**
 * The QR code encodes the bare QR token (the app scans it and sends it to
 * `POST /auth/login-code` as `code`): no URL, no prefix, no student data.
 */
export function qrPayload(entry: Pick<CodeEntry, "qrToken">): string | null {
  const token = entry.qrToken?.trim();
  return token ? token : null;
}

/** "ab3cd9ef2k" -> "AB3CD-9EF2K" (any case, with or without a hyphen). */
export function formatTextCode(raw: string): string {
  const compact = raw.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
  return compact.length > 5 ? `${compact.slice(0, 5)}-${compact.slice(5)}` : compact;
}

/** The sheet if it belongs to this class and has codes to print, otherwise null (the page then says "issue again"). */
export function sheetForClass(sheet: CodeSheet | null | undefined, classId: string): CodeSheet | null {
  if (!sheet || sheet.classId !== classId || sheet.codes.length === 0) return null;
  return sheet;
}

/** Cards per A4 page (2 columns by 4 rows). */
export const CARDS_PER_PAGE = 8;

/** Splits the cards into printable pages. */
export function paginate<T>(items: readonly T[], perPage: number = CARDS_PER_PAGE): T[][] {
  const pages: T[][] = [];
  for (let i = 0; i < items.length; i += perPage) pages.push(items.slice(i, i + perPage));
  return pages;
}

/** Cards in the order students appear on the roster, with the ones that cannot be printed (no code for the chosen kind) listed apart. */
export function splitPrintable(
  codes: readonly CodeEntry[],
  kind: CodeKind,
): { printable: CodeEntry[]; missing: CodeEntry[] } {
  const printable: CodeEntry[] = [];
  const missing: CodeEntry[] = [];
  for (const entry of codes) {
    const ok =
      kind === "QR" ? !!qrPayload(entry) : kind === "TEXT" ? !!entry.textCode : !!qrPayload(entry) || !!entry.textCode;
    (ok ? printable : missing).push(entry);
  }
  return { printable, missing };
}

/** The earliest expiry of the sheet (ISO strings compare chronologically when in UTC), for the "valid until" line. */
export function earliestExpiry(codes: readonly CodeEntry[]): string | null {
  let best: string | null = null;
  for (const c of codes) {
    if (best === null || Date.parse(c.expiresAt) < Date.parse(best)) best = c.expiresAt;
  }
  return best;
}
