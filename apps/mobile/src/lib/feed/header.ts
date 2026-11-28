/**
 * What the band at the top of a feed screen says: a small line above (the school, and the class when it is one) and the
 * large title (a class, a child or the school). A `null` title means "use the screen's own label" ("All children", "School
 * news"): the words are the caller's, so every text stays in the catalog. Pure: no names are invented, only chosen.
 */
export interface HeaderText {
  eyebrow: string | null;
  title: string | null;
}

export interface HeaderChild {
  studentId: string;
  givenName: string;
  schools: readonly { schoolName: string; classes: readonly { name: string }[] }[];
}

const distinct = (values: readonly string[]): string[] => [...new Set(values.filter((value) => value.trim() !== ''))];

/**
 * A guardian's feed. One child (selected, or the only one) is the title, with their school and class above; "all children"
 * has no title of its own, and shows the school above only when every child is in the same one.
 */
export function guardianHeader(kids: readonly HeaderChild[], selectedId: string | null): HeaderText {
  const pool = poolOf(kids, selectedId);
  const classes = distinct(pool.flatMap((kid) => kid.schools.flatMap((school) => school.classes.map((cls) => cls.name))));
  const single = pool.length === 1 ? pool[0]! : null;
  const school = guardianSchool(kids, selectedId);
  const eyebrow = school === null ? null : single && classes.length === 1 ? `${school} · ${classes[0]!}` : school;
  return { eyebrow, title: single ? single.givenName : null };
}

/** The one school the shown children share (it names the round logo), or `null` when they have none or several. */
export function guardianSchool(kids: readonly HeaderChild[], selectedId: string | null): string | null {
  const schools = distinct(poolOf(kids, selectedId).flatMap((kid) => kid.schools.map((school) => school.schoolName)));
  return schools.length === 1 ? schools[0]! : null;
}

/** The selected child, or every child when none is selected (or the selection is stale). */
function poolOf(kids: readonly HeaderChild[], selectedId: string | null): readonly HeaderChild[] {
  const selected = selectedId ? kids.filter((kid) => kid.studentId === selectedId) : [];
  return selected.length > 0 ? selected : kids;
}

/** A class feed (a teacher's or a student's): the class is the title, the school sits above it. */
export function classHeader(schoolName: string | undefined, className: string | undefined): HeaderText {
  return { eyebrow: schoolName?.trim() || null, title: className?.trim() || null };
}

/** The school-wide feed: the school is the title (the caller's "School news" stands in when the name is not known). */
export function schoolHeader(schoolName: string | undefined): HeaderText {
  return { eyebrow: null, title: schoolName?.trim() || null };
}

/** The dropdown of the title is offered only when there is something to choose between. */
export function isSwitchable(options: readonly unknown[]): boolean {
  return options.length > 1;
}
