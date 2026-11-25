/**
 * A student's points as the guardian and student screens show them: school points and home points are two
 * separate sections and are never added together (home points are the family's, not part of class results).
 */

export interface SectionLike {
  total?: number;
}

export interface PointsSections<S extends SectionLike> {
  school: S;
  /** Only present for the student and their guardians. */
  home: S | null;
}

export function sectionsOf<S extends SectionLike>(points: { school: S; home?: S | null }): PointsSections<S> {
  return { school: points.school, home: points.home ?? null };
}

/** Guardians may award home points only with the link's `canAwardPoints`. */
export const canAwardHome = (permissions: { canAwardPoints?: boolean } | undefined): boolean =>
  permissions?.canAwardPoints === true;
