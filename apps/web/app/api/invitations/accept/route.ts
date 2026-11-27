import { postInvitation, relay } from "@/lib/server/auth-upstream";
import { parseInvitationInput } from "@/lib/server/invitation-input";
import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";
import { setSessionCookies } from "@/lib/server/session";
import { tokensFromResponse } from "@/lib/server/session-core";

/**
 * Accepts an invitation with the one-time code and signs the adult in. The tokens go into httpOnly
 * cookies and never into the response body: the browser only learns `{ ok: true }`.
 */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const input = parseInvitationInput(await request.json().catch(() => null), "accept");
  if (!input) return problemResponse(request, "bff_bad_request", 400);

  const upstream = await postInvitation(
    "accept",
    {
      ...input.key,
      phone: input.phone,
      email: input.email,
      country: input.country,
      otpCode: input.otpCode,
      displayName: input.displayName,
      preferredLanguage: input.preferredLanguage,
    },
    localeFromRequest(request),
  );
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  if (!upstream.ok) return relay(upstream);

  const tokens = tokensFromResponse(await upstream.json().catch(() => null));
  if (!tokens) return problemResponse(request, "bff_upstream_unavailable", 502);
  await setSessionCookies(tokens);
  return Response.json({ ok: true }, { headers: { "cache-control": "no-store" } });
}
