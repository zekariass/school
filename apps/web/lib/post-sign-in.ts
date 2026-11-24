import { LOCALE_CODES } from "@school/i18n";
import { sanitizeNextPath } from "./safe-redirect";

/** The part of `GET /me` that decides where a user lands. */
export interface GrantsLike {
  roleAssignments: ReadonlyArray<{
    scope: { type: string };
    schoolSlug?: string | null;
    schoolName?: string | null;
  }>;
}

export interface SchoolChoice {
  slug: string;
  name: string;
}

/** Scope types that get the platform console. */
const CONSOLE_SCOPES = new Set(["PLATFORM", "COUNTRY", "ORGANIZATION"]);

export function hasConsoleAccess(me: GrantsLike): boolean {
  return me.roleAssignments.some((a) => CONSOLE_SCOPES.has(a.scope.type));
}

/** The distinct schools the user holds a SCHOOL or CAMPUS role at, by name. */
export function schoolChoices(me: GrantsLike): SchoolChoice[] {
  const bySlug = new Map<string, SchoolChoice>();
  for (const a of me.roleAssignments) {
    if ((a.scope.type === "SCHOOL" || a.scope.type === "CAMPUS") && a.schoolSlug) {
      bySlug.set(a.schoolSlug, { slug: a.schoolSlug, name: a.schoolName ?? a.schoolSlug });
    }
  }
  return [...bySlug.values()].sort((x, y) => x.name.localeCompare(y.name));
}

/** Only pages of this app ("/en/…"), never API routes or bare paths. */
function isLocalizedPath(path: string): boolean {
  return LOCALE_CODES.some((code) => new RegExp(`^/${code}([/?#]|$)`).test(path));
}

/**
 * Where to send a user right after sign-in:
 *  1. a safe `next` path, if the page that sent them to sign-in gave one;
 *  2. platform / country / organization administrators: the console;
 *  3. exactly one school: that school's workspace;
 *  4. several schools: the school picker;
 *  5. nothing yet: the "no access" page.
 */
export function resolvePostSignInPath(input: {
  me: GrantsLike;
  locale: string;
  next?: string | null;
}): string {
  const next = sanitizeNextPath(input.next);
  if (next && isLocalizedPath(next)) return next;
  const base = `/${input.locale}`;
  if (hasConsoleAccess(input.me)) return `${base}/platform`;
  const schools = schoolChoices(input.me);
  if (schools.length === 1 && schools[0]) return `${base}/s/${encodeURIComponent(schools[0].slug)}`;
  if (schools.length > 1) return `${base}/schools`;
  return `${base}/no-access`;
}
