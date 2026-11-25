import { applyDelta, type Totals } from "@school/classroom-toolkit";
import { signedPoints, type CategoryLike } from "./categories";

export interface StudentTotalLike {
  studentId: string;
  total?: number;
  positiveTotal?: number;
  needsWorkTotal?: number;
}

export interface ClassPointsLike<S extends StudentTotalLike = StudentTotalLike> {
  students: S[];
  total?: number;
  positiveTotal?: number;
  needsWorkTotal?: number;
}

/** The signed amount an award (or, negated, its undo) changes a total by. */
export const awardDelta = (polarity: CategoryLike["polarity"], points: number): number => signedPoints(polarity, points);

/** `studentId -> net total`, the shape `applyDelta` works on. */
export function totalsOf(students: readonly StudentTotalLike[]): Totals {
  return Object.fromEntries(students.map((s) => [s.studentId, s.total ?? 0]));
}

/**
 * The class overview with an award added at once (the projection is eventually consistent, so the next fetch
 * replaces these numbers). `delta` is signed; the positive and needs-work parts follow its sign.
 */
export function applyAwardToOverview<S extends StudentTotalLike, O extends ClassPointsLike<S>>(
  overview: O,
  studentIds: readonly string[],
  delta: number,
): O {
  const ids = new Set(studentIds);
  const totals = applyDelta(totalsOf(overview.students), studentIds, delta);
  const students = overview.students.map((s) =>
    ids.has(s.studentId)
      ? {
          ...s,
          total: totals[s.studentId] ?? 0,
          positiveTotal: (s.positiveTotal ?? 0) + (delta > 0 ? delta : 0),
          needsWorkTotal: (s.needsWorkTotal ?? 0) + (delta < 0 ? delta : 0),
        }
      : s,
  );
  const n = students.filter((s) => ids.has(s.studentId)).length;
  return {
    ...overview,
    students,
    total: (overview.total ?? 0) + delta * n,
    positiveTotal: (overview.positiveTotal ?? 0) + (delta > 0 ? delta * n : 0),
    needsWorkTotal: (overview.needsWorkTotal ?? 0) + (delta < 0 ? delta * n : 0),
  };
}

/** Which students' totals an award touches: the chosen ones, or everybody in the roster for "whole class". */
export function awardTargets(
  mode: { wholeClass: true } | { studentIds: readonly string[] },
  roster: readonly { studentId: string }[],
): string[] {
  return "wholeClass" in mode ? roster.map((s) => s.studentId) : [...mode.studentIds];
}
