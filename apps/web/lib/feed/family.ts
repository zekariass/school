/** What the guardian's home feed needs from `GET /me/children`: which classes the children are in, and who of them. */

export type ClassTeacherRole = "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT";

export interface ChildLike {
  studentId: string;
  displayName: string;
  givenName: string;
  permissions: { canViewAcademics?: boolean };
  schools: readonly {
    schoolId: string;
    schoolName: string;
    classes: readonly { classId: string; name: string; icon?: string | null; teachers?: readonly { teacherId: string; displayName: string; role: ClassTeacherRole }[] }[];
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

/** A class of the family's children, once (siblings share it), with the teachers the API listed for the guardian. */
export interface ClassCard {
  schoolId: string;
  schoolName: string;
  classId: string;
  name: string;
  icon: string | null;
  teachers: { teacherId: string; displayName: string; role: ClassTeacherRole }[];
}

/** The classes of `children` in the API's order, each once. Children whose link hides classes contribute nothing. */
export function classCards(children: readonly ChildLike[]): ClassCard[] {
  const byClass = new Map<string, ClassCard>();
  for (const child of children) {
    if (!child.permissions.canViewAcademics) continue;
    for (const school of child.schools) {
      for (const cls of school.classes) {
        if (byClass.has(cls.classId)) continue;
        byClass.set(cls.classId, {
          schoolId: school.schoolId,
          schoolName: school.schoolName,
          classId: cls.classId,
          name: cls.name,
          icon: cls.icon ?? null,
          teachers: (cls.teachers ?? []).map((t) => ({ teacherId: t.teacherId, displayName: t.displayName, role: t.role })),
        });
      }
    }
  }
  return [...byClass.values()];
}

/**
 * What the banner of the home feed says: a single class gives its name (and icon); several classes of one chosen child
 * are joined; for all children with different classes the title is left to the caller ("All my children"). The
 * subtitle is the school or schools. `/me/feed` has no class filter, so this only names what is shown.
 */
export function bannerOf(cards: readonly ClassCard[], childChosen: boolean): { title: string | null; subtitle: string | null; icon: string | null } {
  const schools = [...new Set(cards.map((c) => c.schoolName))];
  const subtitle = schools.length > 0 ? schools.join(" · ") : null;
  if (cards.length === 1) return { title: cards[0]!.name, subtitle, icon: cards[0]!.icon };
  if (childChosen && cards.length > 1) return { title: cards.map((c) => c.name).join(" · "), subtitle, icon: null };
  return { title: null, subtitle, icon: null };
}
