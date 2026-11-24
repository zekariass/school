import { guardOrigin, localeFromRequest } from "@/lib/server/responses";
import { postAuth } from "@/lib/server/auth-upstream";
import { clearSessionCookies, getRefreshToken } from "@/lib/server/session";

/** Signs this device out: revokes the session at the API, then forgets the cookies. Always 204. */
export async function POST(request: Request) {
  const denied = guardOrigin(request);
  if (denied) return denied;

  const refreshToken = await getRefreshToken();
  if (refreshToken) {
    // Best effort: even if the API is down the cookies go, and the refresh token
    // expires on its own.
    await postAuth("logout", { refreshToken }, localeFromRequest(request));
  }
  await clearSessionCookies();
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}
