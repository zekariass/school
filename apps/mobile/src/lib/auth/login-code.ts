/**
 * Student login codes (M3). A teacher gives a student either a QR code (a 22-character
 * base64url token, case-sensitive) or a text code of 10 characters from a 31-character alphabet
 * (no 0, O, 1, I, L), shown as `XXXXX-XXXXX`. The server accepts the text code in any case, with or
 * without the hyphen. These helpers only deal with what the child types or the camera reads.
 */

export const TEXT_CODE_LENGTH = 10;
const GROUP = 5;
const QR_TOKEN = /^[A-Za-z0-9_-]{22}$/;
/** Alphabet of the text codes (same as the backend's `LoginCodeFormat`). */
export const TEXT_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** Keeps letters and digits only, upper-cased, at most 10 characters ("ahr4p a4ws9" -> "AHR4PA4WS9"). */
export function normalizeTextCode(input: string): string {
  return input
    .replace(/[^A-Za-z0-9]/g, '')
    .toUpperCase()
    .slice(0, TEXT_CODE_LENGTH);
}

/** Types as the child types: "ahr4pa4ws9" -> "AHR4P-A4WS9"; the hyphen appears once there is a second group. */
export function formatTextCode(input: string): string {
  const code = normalizeTextCode(input);
  return code.length <= GROUP ? code : `${code.slice(0, GROUP)}-${code.slice(GROUP)}`;
}

/** True when the text holds a whole 10-character code made of valid characters. */
export function isCompleteTextCode(input: string): boolean {
  const code = normalizeTextCode(input);
  return code.length === TEXT_CODE_LENGTH && [...code].every((char) => TEXT_CODE_ALPHABET.includes(char));
}

export type ScanResult =
  | { kind: 'qr'; code: string }
  | { kind: 'text'; code: string }
  | { kind: 'unknown' };

/**
 * What a scanned QR code contains. Our QR is the bare token; a text code is also accepted (some
 * printouts carry it) and anything else (a web link, a Wi-Fi code, ...) is `unknown` and ignored.
 */
export function classifyScan(data: string | null | undefined): ScanResult {
  const value = (data ?? '').trim();
  if (QR_TOKEN.test(value)) return { kind: 'qr', code: value };
  if (value && isCompleteTextCode(value) && value.replace(/[\s-]/g, '').length === TEXT_CODE_LENGTH) {
    return { kind: 'text', code: formatTextCode(value) };
  }
  return { kind: 'unknown' };
}

/** What to show a teacher for one student: the QR token and the text code as printed. */
export interface CodeCardData {
  displayName: string;
  qrToken: string | null;
  textCode: string | null;
  expiresAt: string | null;
}

/** Display model of an issued code (API `IssuedCode`). */
export function toCodeCard(issued: {
  displayName: string;
  qrToken?: string | undefined;
  textCode?: string | undefined;
  expiresAt?: string | undefined;
}): CodeCardData {
  return {
    displayName: issued.displayName,
    qrToken: issued.qrToken || null,
    textCode: issued.textCode ? formatTextCode(issued.textCode) : null,
    expiresAt: issued.expiresAt ?? null,
  };
}

/** Seconds a scanned code that was not accepted is ignored, so a code held in front of the camera is not sent over and over. */
export const SCAN_COOLDOWN_MS = 2500;
