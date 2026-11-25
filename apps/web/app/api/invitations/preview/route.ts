import { postInvitation, relay } from "@/lib/server/auth-upstream";
import { parseInvitationInput } from "@/lib/server/invitation-input";
import { guardOrigin, localeFromRequest, problemResponse } from "@/lib/server/responses";

/**
 * What an invitation (link token or printed parent code) is about: school, the child's first name,
 * relationship. Public: no session is attached. Any dead invitation is the API's uniform 404.
 */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const input = parseInvitationInput(await request.json().catch(() => null), "key");
  // A key that cannot be one is as dead as an unknown one.
  if (!input) return problemResponse(request, "bff_not_found", 404);

  const upstream = await postInvitation("preview", input.key, localeFromRequest(request));
  if (!upstream) return problemResponse(request, "bff_upstream_unavailable", 502);
  return relay(upstream);
}
