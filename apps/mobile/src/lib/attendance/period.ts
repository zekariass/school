import type { CachedGroup } from '@/lib/offline/cache';
import { isPeriodSlot } from '@/lib/sync/operations';
import { isAttendanceOp, type QueuedOperation } from '@/lib/sync/types';

import { sameRoll, type RollSnapshot } from './roll-model';

/**
 * Period rolls (P1..P8): a subject for the roll and, for an elective, a saved student group.
 *
 * What the API says (M7d-a) and this module follows:
 * - a roll is one per class, date and slot; switching slot never touches another slot's roll;
 * - the subject belongs to the roll: set when it is created, never changed (409 `attendance.subject_mismatch`), and
 *   naming one on a roll that exists without one is refused the same way; only P1..P8 take a subject;
 * - the group is a filter on the request (its members only), not part of the roll's identity.
 */

export { isPeriodSlot };

export interface SubjectChoice {
  /** The subject the next edit carries (null: none). */
  readonly subjectId: string | null;
  /** The subject can no longer be chosen for this roll (it has one, or edits are already waiting without one). */
  readonly locked: boolean;
  /** Whether the roll can name a subject at all (a period slot). */
  readonly applicable: boolean;
}

/**
 * Which subject a new edit of `roll` names. A subject is picked only while the roll is new: no stored roll
 * (`sessionVersion` null) and no waiting edit of it. After the first edit the subject is whatever that edit said,
 * so a second edit can never name another one (the server would refuse it and the teacher's marks with it).
 */
export function subjectChoice(
  roll: Pick<RollSnapshot, 'classId' | 'date' | 'slot' | 'subjectId' | 'sessionVersion'>,
  pending: readonly QueuedOperation[],
  draft: string | null,
): SubjectChoice {
  if (!isPeriodSlot(roll.slot)) return { subjectId: null, locked: true, applicable: false };
  if (roll.subjectId !== null) return { subjectId: roll.subjectId, locked: true, applicable: true };
  const waiting = pending.filter((op) => op.status === 'PENDING' && isAttendanceOp(op) && sameRoll(op, roll));
  if (waiting.length > 0) {
    const named = waiting.map((op) => (isAttendanceOp(op) ? op.payload.subjectId : undefined)).find((id) => id !== undefined);
    return { subjectId: named ?? null, locked: true, applicable: true };
  }
  // A stored roll without a subject: naming one now would be refused.
  if (roll.sessionVersion !== null) return { subjectId: null, locked: true, applicable: true };
  return { subjectId: draft, locked: false, applicable: true };
}

/**
 * The roll restricted to a saved group: its members only. Used for an elective's roll taken from the offline copy;
 * online the API answers the restricted roll itself.
 */
export function restrictToGroup(roll: RollSnapshot, group: Pick<CachedGroup, 'id' | 'studentIds'>): RollSnapshot {
  const members = new Set(group.studentIds);
  return { ...roll, groupId: group.id, students: roll.students.filter((student) => members.has(student.studentId)) };
}

/** The roster of a class restricted to a group, for a roll built from the class list. */
export function groupMembers<T extends { readonly id: string }>(roster: readonly T[], group: Pick<CachedGroup, 'studentIds'>): T[] {
  const members = new Set(group.studentIds);
  return roster.filter((student) => members.has(student.id));
}

/**
 * The subjects a period roll of the class can be taken for: the class's own subjects (`ClassView.subjectIds`) that are
 * still active in the school's catalogue, by name. The API checks the same on write (400 `attendance.invalid_subject`).
 */
export function activeClassSubjects(
  classSubjectIds: readonly string[],
  catalogue: readonly { id: string; name: string; active: boolean }[],
): { id: string; name: string }[] {
  const own = new Set(classSubjectIds);
  return catalogue
    .filter((subject) => subject.active && own.has(subject.id))
    .map((subject) => ({ id: subject.id, name: subject.name }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** The subject of a roll as the screen names it: the stored one, else the pending one from the class's list. */
export function subjectNameOf(
  subjectId: string | null,
  roll: Pick<RollSnapshot, 'subjectId' | 'subjectName'>,
  subjects: readonly { id: string; name: string }[],
): string | null {
  if (subjectId === null) return null;
  if (subjectId === roll.subjectId && roll.subjectName) return roll.subjectName;
  return subjects.find((subject) => subject.id === subjectId)?.name ?? null;
}
