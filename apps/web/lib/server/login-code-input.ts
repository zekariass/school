import { loginCodeToSend } from "@school/validation/login-code";

/** Nothing legitimate is longer than this: a code is 22 characters and the JSON around it a few more. */
export const LOGIN_CODE_BODY_LIMIT = 512;

/**
 * What the class-code sign-in route forwards to the API: exactly `{ code }`, and only when `code` is a bare QR token or a
 * complete text code (canonicalised). Everything else the browser sent (other fields, an object or a number for `code`, a
 * URL, a long text) is dropped or refused, so the route is not a way to push arbitrary data at the API's sign-in. Null
 * when the input cannot be a code; the caller answers 400 and nothing reaches the API (and no rate-limit slot is used).
 */
export function parseLoginCodeInput(input: unknown): { code: string } | null {
  if (typeof input !== "object" || input === null || Array.isArray(input)) return null;
  const raw = (input as Record<string, unknown>).code;
  if (typeof raw !== "string" || raw.length > 64) return null;
  const code = loginCodeToSend(raw);
  return code ? { code } : null;
}
