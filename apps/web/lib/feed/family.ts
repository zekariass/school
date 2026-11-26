/** What the guardian's home feed needs from `GET /me/children`: which classes the children are in, and who of them. */

export interface ChildLike {
  studentId: string;
  displayName: string;
  givenName: string;
  permissions: { canViewAcademics?: boolean };
  schools: readonly {
    schoolId: string;
    schoolName: string;
    classes: readonly { classId: string; name: string; icon?: string | null }[];
  }[];
}

export interface ClassTarget {
  schoolId: string;
  schoolName: string;
  classId: string;
  className: string;
  icon: string | null;
  /** The guardian's children in this class (the API decides which of them may be tagged). */
  children: { studentId: string; name: string }[];
}

/**
 * The classes (one entry each, even for siblings) where the guardian could contribute, from the children whose link
 * allows viewing academics. Only an input to the capabilities queries: the API decides who may contribute.
 */
export function classTargets(children: readonly ChildLike[]): ClassTarget[] {
  const byClass = new Map<string, ClassTarget>();
  for (const child of children) {
    if (!child.permissions.canViewAcademics) continue;
    for (const school of child.schools) {
      for (const cls of school.classes) {
        const entry =
          byClass.get(cls.classId) ??
          ({ schoolId: school.schoolId, schoolName: school.schoolName, classId: cls.classId, className: cls.name, icon: cls.icon ?? null, children: [] } satisfies ClassTarget);
        if (!entry.children.some((c) => c.studentId === child.studentId)) entry.children.push({ studentId: child.studentId, name: child.givenName || child.displayName });
        byClass.set(cls.classId, entry);
      }
    }
  }
  return [...byClass.values()];
}

/** Children shown in the feed filter: those whose link lets the guardian see their classes. */
export function filterableChildren<T extends ChildLike>(children: readonly T[]): T[] {
  return children.filter((c) => c.permissions.canViewAcademics && c.schools.some((s) => s.classes.length > 0));
}

/** The children of `target` the API allows to be tagged (`contributionStudentIds`), in the guardian's order. */
export function taggableChildren(target: ClassTarget, allowedIds: readonly string[]): { studentId: string; name: string }[] {
  const allowed = new Set(allowedIds);
  return target.children.filter((c) => allowed.has(c.studentId));
}
