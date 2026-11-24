import { NextResponse, type NextRequest } from "next/server";
import createIntlProxy from "next-intl/middleware";
import { routing } from "@/i18n/routing";
import { sanitizeNextPath } from "@/lib/safe-redirect";

const handleIntl = createIntlProxy(routing);

/** Cookie holding the refresh token (see lib/server/session.ts; not importable here). */
const REFRESH_TOKEN_COOKIE = "sch_rt";

const PROTECTED = new RegExp(`^/(${routing.locales.join("|")})/(s|platform)(/|$)`);

export default function proxy(request: NextRequest) {
  // 1. Locale negotiation and `/` -> `/{locale}` redirects.
  const response = handleIntl(request);
  if (response.headers.has("location")) return response;

  // 2. Optimistic auth gate: redirect early when no session cookie exists.
  // This is only a UX shortcut. Authorization is enforced by the API.
  const { pathname, search } = request.nextUrl;
  const match = PROTECTED.exec(pathname);
  if (match && !request.cookies.has(REFRESH_TOKEN_COOKIE)) {
    const next = sanitizeNextPath(pathname + search);
    const url = request.nextUrl.clone();
    url.pathname = `/${match[1]}/sign-in`;
    url.search = "";
    if (next) url.searchParams.set("next", next);
    return NextResponse.redirect(url);
  }

  return response;
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico|.*\\..*).*)"],
};
