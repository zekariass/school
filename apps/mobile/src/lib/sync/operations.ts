import {
  isAttendanceOp,
  isAttendanceStatus,
  isUuid,
  type AttendanceMark,
  type AttendancePayload,
  type ConflictEntry,
  type ConflictState,
  type NewOperation,
  type QueuedAttendance,
  type QueuedOperation,
} from './types';

/** Per-operation payload ceiling kept well below the server's 64 KiB. */
export const MAX_PAYLOAD_BYTES = 48 * 1024;

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isCivilDate(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const match = DATE_PATTERN.exec(value);
  if (!match) return false;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
}

const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0;

export function isValidMark(mark: AttendanceMark): boolean {
  if (!isUuid(mark.studentId) || !isAttendanceStatus(mark.status)) return false;
  if (mark.minutesLate !== undefined) {
    if (mark.status !== 'LATE') return false;
    if (!Number.isInteger(mark.minutesLate) || mark.minutesLate < 1 || mark.minutesLate > 600) return false;
  }
  return mark.expectedVersion === undefined || isCount(mark.expectedVersion);
}

/** Whether the engine accepts the operation: well-formed ids, sane sizes, the payload shape of its type. */
export function isValidOperation(op: NewOperation | QueuedOperation): boolean {
  if (!isUuid(op.opId) || !op.ownerId || !isUuid(op.schoolId) || !isUuid(op.classId)) return false;
  if (op.baseVersion !== null && !isCount(op.baseVersion)) return false;
  if (op.type === 'ATTENDANCE_MARK') {
    const { date, slot, records, overrideReason } = op.payload;
    if (!isCivilDate(date) || !slot || slot.length > 10) return false;
    if (records.length === 0 || records.length > 500) return false;
    if (new Set(records.map((record) => record.studentId)).size !== records.length) return false;
    if (!records.every(isValidMark)) return false;
    if (overrideReason !== undefined && (overrideReason.length === 0 || overrideReason.length > 500)) return false;
  } else {
    const { categoryId, studentIds, wholeClass, points } = op.payload;
    if (!isUuid(categoryId)) return false;
    if (points !== null && !(Number.isInteger(points) && points >= 1 && points <= 10)) return false;
    if (wholeClass) {
      if (studentIds.length !== 0) return false;
    } else if (studentIds.length === 0 || studentIds.length > 500 || new Set(studentIds).size !== studentIds.length || !studentIds.every(isUuid)) {
      return false;
    }
  }
  return JSON.stringify(op.payload).length <= MAX_PAYLOAD_BYTES;
}

/** Keeps only the known fields of a payload, so nothing else (a note, a name) can ever reach the disk. */
export function cleanPayload<T extends NewOperation | QueuedOperation>(op: T): T {
  if (op.type === 'ATTENDANCE_MARK') {
    const records = op.payload.records.map((record) => ({
      studentId: record.studentId,
      status: record.status,
      ...(record.minutesLate !== undefined ? { minutesLate: record.minutesLate } : {}),
      ...(record.expectedVersion !== undefined ? { expectedVersion: record.expectedVersion } : {}),
    }));
    const payload: AttendancePayload = {
      date: op.payload.date,
      slot: op.payload.slot,
      records,
      ...(op.payload.overrideReason !== undefined ? { overrideReason: op.payload.overrideReason } : {}),
    };
    return { ...op, payload };
  }
  return { ...op, payload: { ...op.payload, studentIds: [...op.payload.studentIds] } };
}

/**
 * Edits of the same roll (class, date, slot, teacher) that were not sent yet become one operation: a teacher who
 * taps through a class makes one request, not thirty. Awards never merge.
 */
export function mergeKeyOf(op: NewOperation | QueuedOperation): string | null {
  return op.type === 'ATTENDANCE_MARK' ? `${op.ownerId}|${op.classId}|${op.payload.date}|${op.payload.slot}` : null;
}

/**
 * The payload of `existing` with `incoming`'s marks on top: a later mark replaces an earlier one for the same
 * student. The first `baseVersion` stays (the older view is the conservative one) and so does the older
 * `expectedVersion` of a student who was already in the operation (the record the teacher first saw).
 */
export function mergeAttendance(existing: QueuedAttendance, incoming: Omit<QueuedAttendance, 'createdAt' | 'attempts' | 'nextAttemptAt' | 'status'>): AttendancePayload {
  const byStudent = new Map<string, AttendanceMark>(existing.payload.records.map((record) => [record.studentId, record]));
  for (const record of incoming.payload.records) {
    const earlier = byStudent.get(record.studentId);
    const expected = earlier?.expectedVersion ?? record.expectedVersion;
    byStudent.set(record.studentId, {
      studentId: record.studentId,
      status: record.status,
      ...(record.minutesLate !== undefined ? { minutesLate: record.minutesLate } : {}),
      ...(expected !== undefined ? { expectedVersion: expected } : {}),
    });
  }
  const overrideReason = incoming.payload.overrideReason ?? existing.payload.overrideReason;
  return {
    date: existing.payload.date,
    slot: existing.payload.slot,
    records: [...byStudent.values()],
    ...(overrideReason !== undefined ? { overrideReason } : {}),
  };
}

/** The notes of both operations, the later one winning per student. Never persisted. */
export function mergeNotes(
  earlier: Readonly<Record<string, string>> | undefined,
  later: Readonly<Record<string, string>> | undefined,
): Record<string, string> | undefined {
  if (!earlier && !later) return undefined;
  return { ...earlier, ...later };
}

// --- what the server answered for an ATTENDANCE_MARK ---------------------------------------------------------

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

const numberOrNull = (value: unknown): number | null => (typeof value === 'number' && Number.isFinite(value) ? value : null);

/** One outcome of `state.outcomes[]`: the stored record and what was submitted. */
export interface RollOutcome {
  readonly studentId: string;
  readonly outcome: 'APPLIED' | 'UNCHANGED' | 'CONFLICT';
  readonly record: {
    readonly status: AttendanceMark['status'];
    readonly minutesLate: number | null;
    readonly version: number | null;
    readonly recordedBy: string | null;
  };
  readonly submitted: { readonly status: AttendanceMark['status']; readonly minutesLate: number | null } | null;
}

/** Reads `state` of an ATTENDANCE_MARK result (an `AttendanceWriteResult`). Malformed entries are skipped. */
export function parseRollState(state: unknown): { sessionVersion: number | null; outcomes: RollOutcome[] } {
  const root = asRecord(state);
  const outcomes: RollOutcome[] = [];
  const list = root && Array.isArray(root.outcomes) ? root.outcomes : [];
  for (const raw of list) {
    const entry = asRecord(raw);
    const record = asRecord(entry?.record);
    if (!entry || !record || !isUuid(entry.studentId) || !isAttendanceStatus(record.status)) continue;
    const kind = entry.outcome === 'CONFLICT' ? 'CONFLICT' : entry.outcome === 'UNCHANGED' ? 'UNCHANGED' : 'APPLIED';
    const submitted = asRecord(entry.submitted);
    outcomes.push({
      studentId: entry.studentId,
      outcome: kind,
      record: {
        status: record.status,
        minutesLate: numberOrNull(record.minutesLate),
        version: numberOrNull(record.version),
        recordedBy: typeof record.recordedBy === 'string' ? record.recordedBy : null,
      },
      submitted:
        submitted && isAttendanceStatus(submitted.status)
          ? { status: submitted.status, minutesLate: numberOrNull(submitted.minutesLate) }
          : null,
    });
  }
  return { sessionVersion: numberOrNull(root?.sessionVersion), outcomes };
}

/** The clashing marks of a CONFLICT result; null when the state names none. */
export function conflictFromState(state: unknown): ConflictState | null {
  const { sessionVersion, outcomes } = parseRollState(state);
  const entries: ConflictEntry[] = outcomes
    .filter((outcome) => outcome.outcome === 'CONFLICT' && outcome.submitted !== null)
    .map((outcome) => ({
      studentId: outcome.studentId,
      stored: { ...outcome.record },
      submitted: outcome.submitted!,
    }));
  return entries.length > 0 ? { sessionVersion, entries } : null;
}

export { isAttendanceOp };
