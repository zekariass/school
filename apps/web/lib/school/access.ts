/**
 * What a signed-in user may see in a school's workspace, derived from `GET /me`
 * (role grants -> permissions, plus school memberships). This only decides which
 * menu items and buttons to show: the API still checks every call.
 */

export const SP = {
  read: "school.read",
  manage: "school.manage",
  settings: "school.settings.manage",
  academic: "school.academic.manage",
  staff: "school.staff.manage",
  students: "school.students.manage",
  classManage: "class.manage",
} as const;

export interface ScopeLike {
  scope: { type: string };
  schoolSlug?: string | null;
  permissions: readonly string[];
}

export interface MembershipLike {
  type: string;
  schoolSlug: string;
}

export interface MeLike {
  scopes: readonly ScopeLike[];
  memberships?: readonly MembershipLike[];
}

/** Grants above school level apply to every school below them (the API narrows by country or organization). */
const BROAD_SCOPES = new Set(["PLATFORM", "COUNTRY", "ORGANIZATION"]);

export interface SchoolAccess {
  permissions: ReadonlySet<string>;
  isTeacher: boolean;
  isStaff: boolean;
  isStudent: boolean;
}

/** The permissions and memberships that apply to the school behind `slug`. */
export function schoolAccess(me: MeLike, slug: string): SchoolAccess {
  const permissions = new Set<string>();
  for (const s of me.scopes) {
    if (BROAD_SCOPES.has(s.scope.type) || s.schoolSlug === slug) {
      for (const p of s.permissions) permissions.add(p);
    }
  }
  const here = (me.memberships ?? []).filter((m) => m.schoolSlug === slug);
  return {
    permissions,
    isTeacher: here.some((m) => m.type === "TEACHER"),
    isStaff: here.some((m) => m.type === "STAFF"),
    isStudent: here.some((m) => m.type === "STUDENT"),
  };
}

export type NavId =
  | "dashboard"
  | "classes"
  | "myClasses"
  | "students"
  | "teachers"
  | "academic"
  | "settings"
  | "features";

/** The workspace menu for this access, in display order. */
export function navItems(access: SchoolAccess): NavId[] {
  const can = (p: string) => access.permissions.has(p);
  const items: NavId[] = ["dashboard"];
  if (can(SP.classManage)) items.push("classes");
  else if (access.isTeacher) items.push("myClasses");
  if (can(SP.students)) items.push("students");
  if (can(SP.staff)) items.push("teachers");
  if (can(SP.academic)) items.push("academic");
  if (can(SP.settings)) items.push("settings");
  if (can(SP.read)) items.push("features");
  return items;
}

/** Students only use the mobile app: a STUDENT membership without anything else has no workspace. */
export function isStudentOnly(access: SchoolAccess): boolean {
  return access.isStudent && !access.isTeacher && !access.isStaff && access.permissions.size === 0;
}

/** True for teachers who run classes but hold no administrative class permission. */
export function isTeacherOnly(access: SchoolAccess): boolean {
  return access.isTeacher && !access.permissions.has(SP.classManage) && !access.permissions.has(SP.read);
}

/** The route (under /s/{slug}) of each menu item. */
export const NAV_PATH: Record<NavId, string> = {
  dashboard: "",
  classes: "classes",
  myClasses: "classes",
  students: "students",
  teachers: "teachers",
  academic: "academic",
  settings: "settings",
  features: "features",
};
