import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";
import { parseDestination, postAuth, relay } from "@/lib/server/auth-upstream";
import { setSessionCookies } from "@/lib/server/session";
import { tokensFromResponse } from "@/lib/server/session-core";

/**
 * Exchanges a code for a session. The tokens go into httpOnly cookies and never
 * into the response body: the browser only learns `{ ok: true }`.
 */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const input: unknown = await request.json().catch(() => null);
  const destination = parseDestination(input);
  const code =
    typeof input === "object" && input !== null ? (input as { code?: unknown }).code : undefined;
  if (!destination || typeof code !== "string" || !/^\d{6}$/.test(code.trim())) {
    return problemResponse(request, "bff_bad_request", 400);
  }

  const upstream = await postAuth(
    "otp/verify",
    { ...destination, code: code.trim() },
    localeFromRequest(request),
  );
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  if (!upstream.ok) return relay(upstream);

  const tokens = tokensFromResponse(await upstream.json().catch(() => null));
  if (!tokens) return problemResponse(request, "bff_upstream_unavailable", 502);
  await setSessionCookies(tokens);
  return Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
}
