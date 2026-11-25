import { NextResponse, type NextRequest } from "next/server";
import createIntlProxy from "next-intl/middleware";
import { routing } from "@/i18n/routing";
import { sanitizeNextPath } from "@/lib/safe-redirect";
import { getApiOrigin } from "@/lib/server/env";
import { refreshSession } from "@/lib/server/refresh";
import {
  ACCESS_TOKEN_COOKIE,
  REFRESH_TOKEN_COOKIE,
  buildSessionCookieOptions,
  decideRefresh,
  isSecureEnvironment,
} from "@/lib/server/session-core";

const handleIntl = createIntlProxy(routing);

/**
 * Pages that need a session. Everything else is public, notably `/{locale}/invite/{token}`: an
 * invited parent has no account yet (the invitation page and its Route Handlers sign them in).
 */
const PROTECTED = new RegExp(
  `^/(${routing.locales.join("|")})/(s|platform|schools|no-access|student-app|family|points|children)(/|$)`,
);

function signInRedirect(request: NextRequest, locale: string) {
  const { pathname, search } = request.nextUrl;
  const next = sanitizeNextPath(pathname + search);
  const url = request.nextUrl.clone();
  url.pathname = `/${locale}/sign-in`;
  url.search = "";
  if (next) url.searchParams.set("next", next);
  return NextResponse.redirect(url);
}

export default async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const match = PROTECTED.exec(pathname);

  // Not a protected page: locale negotiation and `/` -> `/{locale}` redirects only.
  if (!match) return handleIntl(request);
  const locale = match[1] ?? routing.defaultLocale;

  // Optimistic auth gate: no session cookie at all, go straight to sign-in.
  // This is only a UX shortcut. The API and the layouts' session checks are the authority.
  const refreshToken = request.cookies.get(REFRESH_TOKEN_COOKIE)?.value;
  if (!refreshToken) return signInRedirect(request, locale);

  // Silent refresh: the access token lives 15 minutes, so renew it here, before
  // any Server Component reads it, and hand the new pair to this very request.
  const decision = decideRefresh({
    accessToken: request.cookies.get(ACCESS_TOKEN_COOKIE)?.value,
    refreshToken,
    now: Date.now(),
  });
  let refreshed: Awaited<ReturnType<typeof refreshSession>> | null = null;
  if (decision === "refresh") {
    refreshed = await refreshSession(getApiOrigin(), refreshToken, { locale });
    if (refreshed.status === "rejected") {
      // The session is over (expired, revoked or reused): forget it and sign in again.
      const response = signInRedirect(request, locale);
      response.cookies.delete(ACCESS_TOKEN_COOKIE);
      response.cookies.delete(REFRESH_TOKEN_COOKIE);
      return response;
    }
    // "unavailable": carry on; the layouts show an error if the API stays down.
  }

  if (refreshed?.status === "refreshed") {
    // Server Components of THIS request read cookies from the request headers,
    // so update them before next-intl copies the headers into its response.
    request.cookies.set(ACCESS_TOKEN_COOKIE, refreshed.tokens.accessToken);
    request.cookies.set(REFRESH_TOKEN_COOKIE, refreshed.tokens.refreshToken);
  }

  const response = handleIntl(request);
  if (response.headers.has("location")) return response;

  if (refreshed?.status === "refreshed") {
    const secure = isSecureEnvironment();
    const { tokens } = refreshed;
    // ...and the browser gets the new pair for its next request.
    response.cookies.set(
      ACCESS_TOKEN_COOKIE,
      tokens.accessToken,
      buildSessionCookieOptions(tokens.accessTokenMaxAge, secure),
    );
    response.cookies.set(
      REFRESH_TOKEN_COOKIE,
      tokens.refreshToken,
      buildSessionCookieOptions(tokens.refreshTokenMaxAge, secure),
    );
  }
  return response;
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico|.*\\..*).*)"],
};
