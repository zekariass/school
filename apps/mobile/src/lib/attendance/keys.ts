/** Query keys of attendance and absence-report data. */

export const ATTENDANCE_ROOT = 'attendance';

/** `date` and `slot` are null for "the school's today, first slot" (the server decides which day that is). */
export const rollKey = (classId: string, date: string | null, slot: string | null) =>
  [ATTENDANCE_ROOT, 'roll', classId, date ?? 'today', slot ?? 'first'] as const;
/** Every roll query of a class. */
export const classRollsKey = (classId: string) => [ATTENDANCE_ROOT, 'roll', classId] as const;
export const classAbsencesKey = (classId: string) => [ATTENDANCE_ROOT, 'absences', 'class', classId] as const;
export const studentAbsencesKey = (schoolId: string, studentId: string) => [ATTENDANCE_ROOT, 'absences', 'student', schoolId, studentId] as const;
export const studentHistoryKey = (schoolId: string, studentId: string) => [ATTENDANCE_ROOT, 'history', schoolId, studentId] as const;

/**
 * Nothing about attendance is written to TanStack's persisted cache: children's marks, reports and reasons.
 * The roll's offline copy lives in the explicit, owner-scoped cache table instead (`lib/offline`).
 */
export function isPersistableAttendanceQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== ATTENDANCE_ROOT;
}
