/**
 * Who sees the staff roll. Only what `GET /me` says: the two permissions of M7d, held at a school (the API is the
 * authority and answers 403/404 again on every call). A teacher, as a teacher, holds neither, so no screen or entry
 * point is ever built for one: **a teacher never reads a colleague's record**.
 */

export const STAFF_ATTENDANCE_MANAGE = 'school.staff_attendance.manage';
export const STAFF_ATTENDANCE_READ = 'school.staff_attendance.read';

/** The part of `GET /me` that decides it. */
export interface StaffAccessInput {
  readonly scopes?: readonly {
    readonly permissions: readonly string[];
    readonly schoolName?: string | undefined;
    readonly scope: { readonly type: string; readonly schoolId?: string | undefined };
  }[];
}

export interface StaffAttendanceSchool {
  readonly schoolId: string;
  readonly schoolName: string;
  /** Marks the day and makes corrections. */
  readonly canManage: boolean;
}

/**
 * The schools where the person may see staff attendance, by name: those where they hold `.manage` (which includes
 * reading) or `.read`. Only school-level grants count (they are the ones that name a school; the permissions are
 * given to school administrators and above, never to campus administrators or teachers).
 */
export function staffAttendanceSchools(me: StaffAccessInput | undefined): StaffAttendanceSchool[] {
  const bySchool = new Map<string, StaffAttendanceSchool>();
  for (const entry of me?.scopes ?? []) {
    const schoolId = entry.scope.type === 'SCHOOL' ? entry.scope.schoolId : undefined;
    if (!schoolId) continue;
    const canManage = entry.permissions.includes(STAFF_ATTENDANCE_MANAGE);
    if (!canManage && !entry.permissions.includes(STAFF_ATTENDANCE_READ)) continue;
    const earlier = bySchool.get(schoolId);
    bySchool.set(schoolId, {
      schoolId,
      schoolName: entry.schoolName ?? earlier?.schoolName ?? '',
      canManage: canManage || (earlier?.canManage ?? false),
    });
  }
  return [...bySchool.values()].sort((a, b) => a.schoolName.localeCompare(b.schoolName));
}

/** Whether to show the entry point to the staff roll at all. */
export const hasStaffAttendance = (me: StaffAccessInput | undefined): boolean => staffAttendanceSchools(me).length > 0;

/**
 * What the screen shows when the API refuses: a stable code to a message. The detail the API sends is shown too, so
 * this only decides the headline.
 */
export type StaffRefusal = 'disabled' | 'featureOff' | 'denied' | 'notFound';

export function staffRefusal(status: number | undefined, code: string | undefined): StaffRefusal | null {
  if (code === 'staff_attendance.disabled') return 'disabled';
  if (code === 'attendance.feature_disabled') return 'featureOff';
  if (status === 403) return 'denied';
  if (status === 404) return 'notFound';
  return null;
}
