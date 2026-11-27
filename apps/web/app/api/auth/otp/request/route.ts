import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";
import { parseDestination, postAuth, relay } from "@/lib/server/auth-upstream";

/** Sends a sign-in code. Pass-through: 202 `{codeLength, expiresIn}`, or the API's 400/429 problem. */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const destination = parseDestination(await request.json().catch(() => null));
  if (!destination) return problemResponse(request, "bff_bad_request", 400);

  const upstream = await postAuth(
    "otp/request",
    destination,
    localeFromRequest(request),
  );
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  return relay(upstream);
}
