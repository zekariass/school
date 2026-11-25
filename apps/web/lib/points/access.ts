import { SP } from "@/lib/school/access";

/**
 * What the points screens show to whom, derived from `/me` and the class (pure). Only decides which tabs, menu
 * items and buttons appear: the API still answers 403/404 for everything.
 */

export interface PointsClassLike {
  status: "ACTIVE" | "ARCHIVED";
  myMembership?: "STUDENT" | "TEACHER" | null;
  myTeacherRole?: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT" | null;
}

export interface ClassPointsAbilities {
  /** The Points and Toolkit tabs: teachers of the class and class managers. */
  canUse: boolean;
  /** Award and undo: the same people, while the class is active. */
  canAward: boolean;
  /** Add, rename and deactivate the class's own skills: lead, co-teacher, class managers, points managers. */
  canManageSkills: boolean;
  /** Hand out manual badges: teachers of the class and points managers. */
  canAwardBadges: boolean;
  /** Share a template with the whole school: points managers only. */
  canShareSchoolWide: boolean;
}

export function classPointsAbilities(cls: PointsClassLike, permissions: ReadonlySet<string>): ClassPointsAbilities {
  const classManager = permissions.has(SP.classManage);
  const pointsManager = permissions.has(SP.pointsManage);
  const teaches = cls.myMembership === "TEACHER";
  const canUse = classManager || teaches || pointsManager;
  const leads = teaches && (cls.myTeacherRole === "LEAD" || cls.myTeacherRole === "CO_TEACHER");
  return {
    canUse,
    canAward: canUse && cls.status === "ACTIVE",
    canManageSkills: classManager || pointsManager || leads,
    canAwardBadges: canUse,
    canShareSchoolWide: pointsManager,
  };
}

export interface PointsPageAccess {
  /** Any report at all: teachers (their classes) and points readers. */
  canSeeReport: boolean;
  /** The school-wide report (no class chosen) needs `school.points.read`. */
  canSeeSchoolReport: boolean;
  /** School skills and badges: `school.points.manage`. */
  canManage: boolean;
}

export function pointsPageAccess(permissions: ReadonlySet<string>, isTeacher: boolean): PointsPageAccess {
  const canManage = permissions.has(SP.pointsManage);
  const canSeeSchoolReport = permissions.has(SP.pointsRead) || canManage;
  return { canSeeReport: canSeeSchoolReport || isTeacher, canSeeSchoolReport, canManage };
}

/** The leaderboard is shown only when the school turned it on (the API also answers 403 otherwise). */
export const showLeaderboard = (settings: { leaderboardEnabled?: boolean } | undefined): boolean =>
  settings?.leaderboardEnabled === true;

/** Manual badges need the school's badge feature on. */
export const showBadges = (settings: { badgesEnabled?: boolean } | undefined): boolean =>
  settings?.badgesEnabled === true;
