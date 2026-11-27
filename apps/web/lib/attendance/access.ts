import { SP } from "@/lib/school/access";

/**
 * What the attendance screens show to whom, derived from `/me` and the class (pure). Only decides which tabs,
 * menu items and buttons appear: the API still answers 403/404 for everything.
 */

export interface AttendanceClassLike {
  status: "ACTIVE" | "ARCHIVED";
  myMembership?: "STUDENT" | "TEACHER" | null;
  myTeacherRole?: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT" | null;
}

export interface ClassAttendanceAbilities {
  /** The Attendance and Absences tabs: teachers of the class, class managers, attendance readers and managers. */
  canView: boolean;
  /** Take or correct the roll: teachers of the class (assistants while the school allows it), class managers, attendance managers. */
  canTake: boolean;
  /** Acknowledge or decline absence reports: lead, co and subject teachers and attendance managers. */
  canDecide: boolean;
  /** Reason and note of an absence report are visible to the deciders (and attendance managers); others see dates only. */
  seesAbsenceDetails: boolean;
}

export function classAttendanceAbilities(cls: AttendanceClassLike, permissions: ReadonlySet<string>): ClassAttendanceAbilities {
  const classManager = permissions.has(SP.classManage);
  const manager = permissions.has(SP.attendanceManage);
  const reader = permissions.has(SP.attendanceRead);
  const teaches = cls.myMembership === "TEACHER";
  const decidingTeacher = teaches && (cls.myTeacherRole === "LEAD" || cls.myTeacherRole === "CO_TEACHER" || cls.myTeacherRole === "SUBJECT_TEACHER");
  const canDecide = manager || decidingTeacher;
  return {
    canView: classManager || teaches || manager || reader,
    canTake: (classManager || teaches || manager) && cls.status === "ACTIVE",
    canDecide,
    seesAbsenceDetails: canDecide,
  };
}

export interface AttendancePageAccess {
  /** The page at all: students' attendance (below) or the staff tab. */
  canSee: boolean;
  /** The dashboard, the reports and the Periods tab: teachers (their classes) and attendance readers and managers. */
  canSeeStudents: boolean;
  /** The school-wide report (no class chosen) and a student filter without a class need `school.attendance.read|manage`. */
  canSeeSchoolWide: boolean;
  /** The Staff tab: `school.staff_attendance.read|manage`. Never a teacher, whatever else they hold. */
  canSeeStaff: boolean;
  /** Marking staff and correcting their records: `school.staff_attendance.manage`. */
  canManageStaff: boolean;
}

export function attendancePageAccess(permissions: ReadonlySet<string>, isTeacher: boolean): AttendancePageAccess {
  const canSeeSchoolWide = permissions.has(SP.attendanceRead) || permissions.has(SP.attendanceManage);
  const canManageStaff = permissions.has(SP.staffAttendanceManage);
  const canSeeStaff = canManageStaff || permissions.has(SP.staffAttendanceRead);
  const canSeeStudents = canSeeSchoolWide || isTeacher;
  return { canSee: canSeeStudents || canSeeStaff, canSeeStudents, canSeeSchoolWide, canSeeStaff, canManageStaff };
}

/** Guardians see their child's attendance history only with `can_view_academics`. */
export const canSeeChildAttendance = (permissions: { canViewAcademics: boolean }): boolean => permissions.canViewAcademics;
