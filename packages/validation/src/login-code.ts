/**
 * Student login codes (M3), shared by the web sign-in (D41) and anything else that reads one. A teacher gives a student
 * either a QR code (a 22-character base64url token, case-sensitive) or a text code of 10 characters from a 31-character
 * alphabet (no 0, O, 1, I, L), shown as `XXXXX-XXXXX`. The server accepts the text code in any case, with or without the
 * hyphen. These helpers only deal with what the child types or the camera reads; the API decides whether a code is live.
 */

export const LOGIN_TEXT_CODE_LENGTH = 10;
const GROUP = 5;
const QR_TOKEN = /^[A-Za-z0-9_-]{22}$/;
/** Alphabet of the text codes (the same as the backend's `LoginCodeFormat`). */
export const LOGIN_TEXT_CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** Keeps letters and digits only, upper-cased, at most 10 characters ("ahr4p a4ws9" gives "AHR4PA4WS9"). */
export function normalizeLoginTextCode(input: string): string {
  return input
    .replace(/[^A-Za-z0-9]/g, '')
    .toUpperCase()
    .slice(0, LOGIN_TEXT_CODE_LENGTH);
}

/** As the child types: "ahr4pa4ws9" gives "AHR4P-A4WS9"; the hyphen appears with the second group. */
export function formatLoginTextCode(input: string): string {
  const code = normalizeLoginTextCode(input);
  return code.length <= GROUP ? code : `${code.slice(0, GROUP)}-${code.slice(GROUP)}`;
}

/** True when the text holds a whole 10-character code made of valid characters. */
export function isCompleteLoginTextCode(input: string): boolean {
  const code = normalizeLoginTextCode(input);
  return code.length === LOGIN_TEXT_CODE_LENGTH && [...code].every((char) => LOGIN_TEXT_CODE_ALPHABET.includes(char));
}

/** True for the bare token a QR code carries. */
export function isLoginQrToken(input: string): boolean {
  return QR_TOKEN.test(input.trim());
}

export type LoginCodeScan = { kind: 'qr'; code: string } | { kind: 'text'; code: string } | { kind: 'unknown' };

/**
 * What a scanned QR code contains. Our QR is the bare token; a text code is also accepted (some printouts carry it) and
 * anything else (a web link, a Wi-Fi code, a long text) is `unknown` and ignored: nothing but a code is ever sent anywhere.
 */
export function classifyLoginScan(data: string | null | undefined): LoginCodeScan {
  const value = (data ?? '').trim();
  if (isLoginQrToken(value)) return { kind: 'qr', code: value };
  if (value && isCompleteLoginTextCode(value) && value.replace(/[\s-]/g, '').length === LOGIN_TEXT_CODE_LENGTH) {
    return { kind: 'text', code: formatLoginTextCode(value) };
  }
  return { kind: 'unknown' };
}

/**
 * The code to send for whatever was submitted: the token as it is, a text code in its canonical `XXXXX-XXXXX` form, or null
 * when it cannot be a code (nothing is sent then).
 */
export function loginCodeToSend(input: string | null | undefined): string | null {
  const scan = classifyLoginScan(input);
  return scan.kind === 'unknown' ? null : scan.code;
}
