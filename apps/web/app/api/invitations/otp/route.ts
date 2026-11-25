import { postInvitation, relay } from "@/lib/server/auth-upstream";
import { parseInvitationInput } from "@/lib/server/invitation-input";
import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";

/** Sends a one-time code to the phone or email of the adult accepting an invitation (202, or the API's problem). */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const input = parseInvitationInput(await request.json().catch(() => null), "destination");
  if (!input) return problemResponse(request, "bff_bad_request", 400);

  const upstream = await postInvitation(
    "otp",
    { ...input.key, phone: input.phone, email: input.email, country: "ET" },
    localeFromRequest(request),
  );
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  return relay(upstream);
}
