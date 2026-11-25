import { LOCALE_CODES } from "@school/i18n";
import { sanitizeNextPath } from "./safe-redirect";

/** The part of `GET /me` that decides where a user lands. */
export interface GrantsLike {
  roleAssignments: ReadonlyArray<{
    scope: { type: string };
    schoolSlug?: string | null;
    schoolName?: string | null;
  }>;
  /** School memberships (teachers, staff, students); they carry no role assignment. */
  memberships?: ReadonlyArray<{ type: string; schoolSlug: string; schoolName: string }>;
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

/** Membership types that get a web workspace; students use the mobile app. */
const WORKSPACE_MEMBERSHIPS = new Set(["TEACHER", "STAFF"]);

/** The distinct schools the user holds a SCHOOL or CAMPUS role or a teacher/staff membership at, by name. */
export function schoolChoices(me: GrantsLike): SchoolChoice[] {
  const bySlug = new Map<string, SchoolChoice>();
  for (const a of me.roleAssignments) {
    if ((a.scope.type === "SCHOOL" || a.scope.type === "CAMPUS") && a.schoolSlug) {
      bySlug.set(a.schoolSlug, { slug: a.schoolSlug, name: a.schoolName ?? a.schoolSlug });
    }
  }
  for (const m of me.memberships ?? []) {
    if (WORKSPACE_MEMBERSHIPS.has(m.type) && m.schoolSlug && !bySlug.has(m.schoolSlug)) {
      bySlug.set(m.schoolSlug, { slug: m.schoolSlug, name: m.schoolName || m.schoolSlug });
    }
  }
  return [...bySlug.values()].sort((x, y) => x.name.localeCompare(y.name));
}

/** Only pages of this app ("/en/…"), never API routes or bare paths. */
function isLocalizedPath(path: string): boolean {
  return LOCALE_CODES.some((code) => new RegExp(`^/${code}([/?#]|$)`).test(path));
}

/** True when the user is enrolled as a student somewhere. */
export function hasStudentMembership(me: GrantsLike): boolean {
  return (me.memberships ?? []).some((m) => m.type === "STUDENT");
}

/** True when the user is connected to a child as a guardian (an ACTIVE link). */
export function hasGuardianMembership(me: GrantsLike): boolean {
  return (me.memberships ?? []).some((m) => m.type === "GUARDIAN");
}

/**
 * Where to send a user right after sign-in:
 *  1. a safe `next` path, if the page that sent them to sign-in gave one;
 *  2. platform / country / organization administrators: the console;
 *  3. exactly one school: that school's workspace;
 *  4. several schools: the school picker;
 *  5. a guardian of a child: "My children";
 *  6. only a student membership: the "students use the School app" page;
 *  7. nothing yet: "My children" too (a parent whose request still waits for the school has no
 *     membership, and the page explains what happens and lets them connect with a code).
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
  if (hasGuardianMembership(input.me)) return `${base}/family`;
  if (hasStudentMembership(input.me)) return `${base}/student-app`;
  return `${base}/family`;
}
