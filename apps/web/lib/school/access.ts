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
  importManage: "school.import.manage",
  classManage: "class.manage",
  guardians: "school.guardians.manage",
  policies: "school.policies.manage",
  pointsManage: "school.points.manage",
  pointsRead: "school.points.read",
  attendanceManage: "school.attendance.manage",
  attendanceRead: "school.attendance.read",
  feedManage: "school.feed.manage",
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
  | "import"
  | "academic"
  | "settings"
  | "features"
  | "families"
  | "attendance"
  | "points"
  | "feed"
  | "policies";

/** The workspace menu for this access, in display order. */
export function navItems(access: SchoolAccess): NavId[] {
  const can = (p: string) => access.permissions.has(p);
  const items: NavId[] = ["dashboard"];
  if (can(SP.classManage)) items.push("classes");
  else if (access.isTeacher) items.push("myClasses");
  if (can(SP.students)) items.push("students");
  if (can(SP.staff)) items.push("teachers");
  if (can(SP.importManage)) items.push("import");
  if (can(SP.academic)) items.push("academic");
  // Teachers confirm and invite guardians of their own classes (the API decides how far, by lead role and school setting).
  if (can(SP.guardians) || access.isTeacher) items.push("families");
  // Attendance dashboard and reports: teachers (their classes), attendance readers and managers.
  if (can(SP.attendanceManage) || can(SP.attendanceRead) || access.isTeacher) items.push("attendance");
  // Points and badges: teachers (reports of their classes), points managers and readers.
  if (can(SP.pointsManage) || can(SP.pointsRead) || access.isTeacher) items.push("points");
  // The school feed and its approvals queue: members who work at the school (the API decides who may post).
  if (canOpenFeed(access)) items.push("feed");
  if (can(SP.policies)) items.push("policies");
  if (can(SP.settings)) items.push("settings");
  if (can(SP.read)) items.push("features");
  return items;
}

/** The school feed page: teachers, other staff and school feed managers. */
export function canOpenFeed(access: SchoolAccess): boolean {
  return access.isTeacher || access.isStaff || access.permissions.has(SP.feedManage);
}

/** Posting to the whole school: the feed managers' own permission. */
export function canPostSchoolWide(access: Pick<SchoolAccess, "permissions">): boolean {
  return access.permissions.has(SP.feedManage);
}

/**
 * The approvals queue of family contributions: the class's lead teachers and managers (the API narrows it to the
 * classes the caller leads or manages and answers 403/404 otherwise).
 */
export function canOpenApprovals(access: SchoolAccess): boolean {
  return access.isTeacher || access.permissions.has(SP.feedManage) || access.permissions.has(SP.classManage);
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
  import: "import",
  academic: "academic",
  settings: "settings",
  features: "features",
  families: "families",
  attendance: "attendance",
  points: "points",
  feed: "feed",
  policies: "policies",
};
