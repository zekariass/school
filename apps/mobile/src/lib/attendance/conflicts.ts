import type { AttendanceMark, ConflictEntry, ConflictState, NewOperation, QueuedAttendance } from '@/lib/sync/types';

/**
 * A CONFLICT means a colleague marked a student differently while this device was offline. Nothing was
 * overwritten: the server kept its mark, and this device's mark waits as an entry of the conflict.
 *
 * - "Keep theirs": the entry is simply forgotten; the stored mark stays.
 * - "Use mine": the mark is sent again as a NEW operation that names the version of the record the teacher has
 *   now seen (`expectedVersion` = the stored one), so it is written exactly when nobody changed it again.
 */

export type ConflictChoice = 'keep-theirs' | 'use-mine';

export interface Resolution {
  /** What is left to decide (null when nothing is). */
  readonly remaining: ConflictState | null;
  /** The operation to queue for "use mine" (absent for "keep theirs"). */
  readonly resend?: NewOperation;
}

export function markFromEntry(entry: ConflictEntry): AttendanceMark {
  return {
    studentId: entry.studentId,
    status: entry.submitted.status,
    ...(entry.submitted.status === 'LATE' && entry.submitted.minutesLate ? { minutesLate: entry.submitted.minutesLate } : {}),
    ...(entry.stored.version !== null ? { expectedVersion: entry.stored.version } : {}),
  };
}

/** Settles the clashes of `studentIds` of a conflicted operation. `newOpId` is the id for the resend, made once. */
export function resolveConflict(
  op: QueuedAttendance,
  studentIds: readonly string[],
  choice: ConflictChoice,
  newOpId: string,
): Resolution {
  const conflict = op.conflict;
  if (!conflict) return { remaining: null };
  const chosen = new Set(studentIds);
  const settled = conflict.entries.filter((entry) => chosen.has(entry.studentId));
  const left = conflict.entries.filter((entry) => !chosen.has(entry.studentId));
  const remaining = left.length > 0 ? { sessionVersion: conflict.sessionVersion, entries: left } : null;
  if (choice === 'keep-theirs' || settled.length === 0) return { remaining };
  return {
    remaining,
    resend: {
      type: 'ATTENDANCE_MARK',
      opId: newOpId,
      ownerId: op.ownerId,
      schoolId: op.schoolId,
      classId: op.classId,
      baseVersion: conflict.sessionVersion,
      payload: {
        date: op.payload.date,
        slot: op.payload.slot,
        records: settled.map(markFromEntry),
        ...(op.payload.overrideReason !== undefined ? { overrideReason: op.payload.overrideReason } : {}),
      },
    },
  };
}

/** Whether the colleague's mark and this device's mark say the same (then there is nothing to decide). */
export function sameMark(entry: ConflictEntry): boolean {
  return entry.stored.status === entry.submitted.status && (entry.stored.minutesLate ?? null) === (entry.submitted.minutesLate ?? null);
}
