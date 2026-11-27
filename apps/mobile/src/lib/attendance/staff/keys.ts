/**
 * Query keys of staff attendance (M7d). Staff names, statuses and notes are personal data of employees (a note can say
 * anything about a person): they live in TanStack's memory only and are dropped at sign-out. Nothing of it reaches the
 * persisted cache, the offline cache or the outbox: staff attendance is online only.
 */
export const STAFF_ATTENDANCE_ROOT = 'staff-attendance';

export const staffSchoolKey = (schoolId: string) => [STAFF_ATTENDANCE_ROOT, 'school', schoolId] as const;
export const staffDayKey = (schoolId: string, date: string) => [STAFF_ATTENDANCE_ROOT, 'day', schoolId, date] as const;
/** Every loaded page of every day of a school: what a write refreshes. */
export const staffDaysKey = (schoolId: string) => [STAFF_ATTENDANCE_ROOT, 'day', schoolId] as const;
export const staffRecordKey = (schoolId: string, recordId: string) => [STAFF_ATTENDANCE_ROOT, 'record', schoolId, recordId] as const;

/** Whether a query with this key may be written to the persisted cache (SQLite): never for staff attendance. */
export function isPersistableStaffAttendanceQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== STAFF_ATTENDANCE_ROOT;
}
