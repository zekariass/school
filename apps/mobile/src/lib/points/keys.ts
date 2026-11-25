/** Query keys of the points and toolkit data. */

export const POINTS_ROOT = 'points';

export const categoriesKey = (schoolId: string, classId: string) => [POINTS_ROOT, 'categories', schoolId, classId] as const;
export const classPointsKey = (classId: string) => [POINTS_ROOT, 'class', classId] as const;
export const studentPointsKey = (schoolId: string, studentId: string) => [POINTS_ROOT, 'student', schoolId, studentId] as const;
export const timelineKey = (schoolId: string, studentId: string, section: 'SCHOOL' | 'HOME') =>
  [POINTS_ROOT, 'timeline', schoolId, studentId, section] as const;
export const homeCategoriesKey = (schoolId: string, studentId: string) => [POINTS_ROOT, 'home-categories', schoolId, studentId] as const;
export const templatesKey = (schoolId: string, classId: string) => [POINTS_ROOT, 'templates', schoolId, classId] as const;
export const classGroupsKey = (classId: string) => [POINTS_ROOT, 'groups', classId] as const;

/**
 * Nothing about points is written to TanStack's persisted cache: totals, timelines, notes and badges describe
 * children's school life. The skill palettes, the class totals and the routine templates a teacher needs offline
 * live in the explicit, owner-scoped cache table instead (`lib/offline`), which is wiped at sign-out.
 */
export function isPersistablePointsQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== POINTS_ROOT;
}
