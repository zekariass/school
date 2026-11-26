import { SP } from "@/lib/school/access";

export interface SchoolEntry {
  id: string;
  name: string;
  slug: string;
}

interface MembershipLike {
  type: string;
  schoolId: string;
  schoolName: string;
  schoolSlug: string;
}

interface ScopeLike {
  scope: { type: string; schoolId?: string | null };
  schoolName?: string | null;
  schoolSlug?: string | null;
  permissions: readonly string[];
}

export interface CalendarPeople {
  memberships: readonly MembershipLike[];
  scopes: readonly ScopeLike[];
}

function unique(entries: SchoolEntry[]): SchoolEntry[] {
  const seen = new Map<string, SchoolEntry>();
  for (const e of entries) if (!seen.has(e.id)) seen.set(e.id, e);
  return [...seen.values()];
}

/** Every school the person is part of (any membership, or a grant on that school): the calendar's school filter. */
export function calendarSchools(me: CalendarPeople): SchoolEntry[] {
  const fromMemberships = me.memberships.map((m) => ({ id: m.schoolId, name: m.schoolName, slug: m.schoolSlug }));
  const fromGrants = me.scopes
    .filter((s) => s.scope.schoolId && s.schoolSlug)
    .map((s) => ({ id: s.scope.schoolId!, name: s.schoolName ?? s.schoolSlug!, slug: s.schoolSlug! }));
  return unique([...fromMemberships, ...fromGrants]);
}

/**
 * The schools where the person works: teacher or staff memberships, or the calendar-manager permission. These are the
 * places where school events may be offered (the API's capabilities still decide what exactly).
 */
export function calendarWorkSchools(me: CalendarPeople): SchoolEntry[] {
  const fromMemberships = me.memberships
    .filter((m) => m.type === "TEACHER" || m.type === "STAFF")
    .map((m) => ({ id: m.schoolId, name: m.schoolName, slug: m.schoolSlug }));
  const fromGrants = me.scopes
    .filter((s) => s.scope.schoolId && s.schoolSlug && s.permissions.includes(SP.calendarManage))
    .map((s) => ({ id: s.scope.schoolId!, name: s.schoolName ?? s.schoolSlug!, slug: s.schoolSlug! }));
  return unique([...fromMemberships, ...fromGrants]);
}

/** A guardian has a guardian membership (the child filter is theirs). */
export function isCalendarGuardian(me: Pick<CalendarPeople, "memberships">): boolean {
  return me.memberships.some((m) => m.type === "GUARDIAN");
}
