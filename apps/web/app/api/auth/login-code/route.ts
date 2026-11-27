import { postAuth, relay } from "@/lib/server/auth-upstream";
import { LOGIN_CODE_BODY_LIMIT, parseLoginCodeInput } from "@/lib/server/login-code-input";
import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";
import { setSessionCookies } from "@/lib/server/session";
import { tokensFromResponse } from "@/lib/server/session-core";

/**
 * Signs a student in with the class code (QR token or text code) their teacher gave them (D41).
 *
 * - Same-origin requests only (`guardOrigin`).
 * - The input is whitelisted (`parseLoginCodeInput`): exactly `{ code }`, a QR token or a complete text code; anything else is
 *   a 400 and never reaches the API.
 * - The call carries no session (a signed-in person at a shared computer is treated like anyone else) and no
 *   `Idempotency-Key`: a sign-in is not an "issue" call and a repeat must not replay an answer.
 * - The API's answer for a failure is relayed as it is (status, problem, `Retry-After`): every wrong, expired or revoked code,
 *   and a school that is not ACTIVE, is the same 400 `auth.invalid_code`, so this route cannot tell them apart either.
 * - On success the tokens go into httpOnly cookies (as in the OTP route) and never into the response body: the browser only
 *   learns `{ ok: true }`. The code is never logged, stored or put in a URL here.
 */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const declared = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > LOGIN_CODE_BODY_LIMIT) return problemResponse(request, "bff_bad_request", 400);
  const text = await request.text().catch(() => "");
  if (text.length > LOGIN_CODE_BODY_LIMIT) return problemResponse(request, "bff_bad_request", 400);
  let body: unknown = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  const input = parseLoginCodeInput(body);
  if (!input) return problemResponse(request, "bff_bad_request", 400);

  const upstream = await postAuth("login-code", input, localeFromRequest(request));
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  if (!upstream.ok) return relay(upstream);

  const tokens = tokensFromResponse(await upstream.json().catch(() => null));
  if (!tokens) return problemResponse(request, "bff_upstream_unavailable", 502);
  await setSessionCookies(tokens);
  return Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
}
