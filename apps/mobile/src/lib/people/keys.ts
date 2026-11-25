/** Query keys of the class and people data. */

export const CLASSES_ROOT = 'classes';
export const STUDENT_SEARCH_ROOT = 'student-search';
export const STUDENT_ROOT = 'student';

export const myClassesKey = [CLASSES_ROOT, 'mine'] as const;
export const classKey = (classId: string) => [CLASSES_ROOT, 'detail', classId] as const;
export const rosterKey = (classId: string) => [CLASSES_ROOT, 'roster', classId] as const;
export const classTeachersKey = (classId: string) => [CLASSES_ROOT, 'teachers', classId] as const;
export const studentKey = (schoolId: string, studentId: string) => [STUDENT_ROOT, schoolId, studentId] as const;
export const studentSearchKey = (schoolId: string, q: string) => [STUDENT_SEARCH_ROOT, schoolId, { q }] as const;

/**
 * Classes, rosters (name and avatar only) and teachers are cached for offline use. Student records
 * (date of birth, gender, username) and school-wide student searches are not written to the persisted
 * cache; sign-out clears everything.
 */
export function isPersistablePeopleQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== STUDENT_ROOT && queryKey[0] !== STUDENT_SEARCH_ROOT;
}
