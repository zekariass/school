import { NextResponse, type NextRequest } from "next/server";
import { isSupportedLocale } from "@school/i18n";
import { sanitizeNextPath } from "@/lib/safe-redirect";
import { clearSessionCookies } from "@/lib/server/session";

/**
 * Landing point when a Server Component finds the session invalid (401 from the
 * API): Server Components can't clear cookies, so they redirect here, and this
 * handler drops the stale cookies and sends the user to sign in. Clearing
 * cookies on GET is harmless (worst case: an unwanted sign-out).
 */
export async function GET(request: NextRequest) {
  const requested = request.nextUrl.searchParams.get("locale") ?? "";
  const locale = isSupportedLocale(requested) ? requested : "en";
  const next = sanitizeNextPath(request.nextUrl.searchParams.get("next"));

  await clearSessionCookies();
  const url = request.nextUrl.clone();
  url.pathname = `/${locale}/sign-in`;
  url.search = "";
  if (next) url.searchParams.set("next", next);
  return NextResponse.redirect(url, { headers: { "cache-control": "no-store" } });
}
