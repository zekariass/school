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
 * Only the skill palettes are cached for offline use (a teacher must be able to award with no connection).
 * Totals, timelines, notes and badges describe children's school life: never written to the persisted cache.
 * Routine templates are plain lessons, not child data, and persist too.
 */
export function isPersistablePointsQuery(queryKey: readonly unknown[]): boolean {
  if (queryKey[0] !== POINTS_ROOT) return true;
  return queryKey[1] === 'categories' || queryKey[1] === 'templates';
}
