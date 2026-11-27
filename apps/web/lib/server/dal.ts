import "server-only";
import { cache } from "react";
import { notFound, redirect, unstable_rethrow } from "next/navigation";
import { connection } from "next/server";
import { getLocale } from "next-intl/server";
import type { components } from "@school/api-client";
import { SP, schoolAccess } from "@/lib/school/access";
import { createServerApi } from "./api";
import { getAccessToken, getRefreshToken } from "./session";

export type Me = components["schemas"]["MeResponse"];
export type SchoolSummary = components["schemas"]["SchoolSummary"];
export type CountryConfig = components["schemas"]["CountryConfig"];

export type SessionResult =
  | { status: "ok"; me: Me }
  /** No token, or the API rejected it (expired, revoked, account inactive). */
  | { status: "unauthenticated" }
  /** The API could not be reached or failed: not the user's fault. */
  | { status: "unavailable" };

/**
 * The signed-in user, from `GET /me` (the API is the authority). Memoized per
 * request with React `cache`, so layouts and pages can all ask.
 *
 * Read cookies only inside <Suspense> (cacheComponents): call this from a child
 * of a Suspense boundary, never at the top of a layout.
 */
export const getSession = cache(async (): Promise<SessionResult> => {
  // Outside the try: reading cookies is what makes this render dynamic, and
  // Next signals that by throwing, which must not be swallowed.
  if (!(await getAccessToken())) {
    // No access token but a refresh token: the proxy's refresh failed transiently
    // (API down), so the session may be fine. Don't sign anyone out for that.
    return (await getRefreshToken()) ? { status: "unavailable" } : { status: "unauthenticated" };
  }
  // Per-user data from the API must never be part of a (runtime) prerender.
  await connection();
  try {
    const api = await createServerApi();
    const { data, response } = await api.GET("/api/v1/me");
    if (data) return { status: "ok", me: data };
    if (response.status === 401) return { status: "unauthenticated" };
    return { status: "unavailable" };
  } catch (error) {
    unstable_rethrow(error);
    return { status: "unavailable" };
  }
});

/** Where invalid sessions go: clears the stale cookies, then shows sign-in. */
async function signInRedirect(): Promise<never> {
  const locale = await getLocale();
  redirect(`/api/auth/expired?locale=${encodeURIComponent(locale)}`);
}

/**
 * The signed-in user, or a redirect to sign-in. An unreachable API throws, so the
 * nearest error boundary offers a retry instead of bouncing users to sign-in.
 */
export async function requireSession(): Promise<Me> {
  const session = await getSession();
  if (session.status === "ok") return session.me;
  if (session.status === "unauthenticated") return signInRedirect();
  throw new Error("The School API is unavailable.");
}

/**
 * The school behind a workspace slug, for a caller who can read it. Unknown slugs
 * and schools the caller may not read are both 404 (the API answers the same),
 * so URLs reveal nothing about which schools exist.
 */
export const getSchoolBySlug = cache(async (slug: string): Promise<SchoolSummary> => {
  await connection();
  const api = await createServerApi();
  let result;
  try {
    result = await api.GET("/api/v1/schools/by-slug/{slug}", { params: { path: { slug } } });
  } catch (error) {
    unstable_rethrow(error);
    throw new Error("The School API is unavailable.");
  }
  if (result.data) return result.data;
  if (result.response.status === 401) return signInRedirect();
  if (result.response.status === 404 || result.response.status === 403) notFound();
  throw new Error("The School API is unavailable.");
});

/** The school a workspace page shows: what the layout, dashboard and client components need. */
export interface WorkspaceSchool {
  id: string;
  slug: string;
  name: string;
  logo?: SchoolSummary["logo"];
  /** The school's own IANA zone: school dates and times are shown in it (D42). */
  timezone: string;
  /** Default calendar of the school (an API calendar code). */
  defaultCalendarCode: string;
  /** The school's country: its calendars, languages and calling code. */
  country: CountryConfig;
}

/**
 * The school behind a workspace slug for the signed-in user. People who may read the
 * school (`school.read`) get it from `GET /schools/by-slug` (unknown or forbidden is
 * 404). Teachers and other members have no `school.read`, so for them the id, name and
 * slug come from their `memberships` in `GET /me`. No membership and no grant: 404.
 */
export const getWorkspaceSchool = cache(async (slug: string): Promise<WorkspaceSchool> => {
  const me = await requireSession();
  const access = schoolAccess(me, slug);
  if (access.permissions.has(SP.read)) return getSchoolBySlug(slug);
  const membership = me.memberships.find((m) => m.schoolSlug === slug);
  if (!membership) notFound();
  // `/me` sends the configuration of every membership's country next to the memberships.
  const country = me.countries.find((c) => c.code === membership.countryCode);
  if (!country) throw new Error("The School API is unavailable.");
  return {
    id: membership.schoolId,
    slug: membership.schoolSlug,
    name: membership.schoolName,
    timezone: membership.timezone,
    defaultCalendarCode: country.defaultCalendarCode,
    country,
  };
});

/** Permission codes the user holds at any scope. */
export function permissionsOf(me: Me): Set<string> {
  return new Set(me.scopes.flatMap((s) => s.permissions));
}
