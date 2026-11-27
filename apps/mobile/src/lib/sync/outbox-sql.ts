import type { OutboxStorage } from './engine';
import {
  isAttendanceStatus,
  type AttendanceMark,
  type AttendancePayload,
  type AwardPayload,
  type ConflictEntry,
  type ConflictState,
  type OperationProblem,
  type OperationStatus,
  type OperationType,
  type QueuedOperation,
} from './types';

/** The subset of expo-sqlite's database the outbox needs (so a test can back it with another SQLite). */
export interface OutboxDatabase {
  runAsync(sql: string, params: (string | number | null)[]): Promise<unknown>;
  getAllAsync<T>(sql: string, params: (string | number | null)[]): Promise<T[]>;
}

/**
 * Migration 3 of the local database: the generic outbox. It replaces the M6c award queue (`points_award_queue`)
 * and the never-used `sync_outbox` placeholder of migration 1. Rows of an installed M6c build are copied over
 * with their operation ids, so an award that was queued before the update is sent exactly once: the server
 * recognises the id (`clientOpId` of the award is the sync operation id) and answers DUPLICATE when the
 * old build had already got it through.
 *
 * Columns hold ids, statuses, numbers and times, plus the payload (ids and marks). No notes, names or tokens.
 */
export const OUTBOX_STATEMENTS: readonly string[] = [
  `CREATE TABLE outbox_operations (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    op_id TEXT NOT NULL UNIQUE,
    owner_id TEXT NOT NULL,
    op_type TEXT NOT NULL,
    school_id TEXT NOT NULL,
    class_id TEXT NOT NULL,
    base_version INTEGER,
    payload TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    problem_status INTEGER,
    problem_code TEXT,
    problem_detail TEXT,
    conflict TEXT,
    created_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL DEFAULT 0,
    settled_at INTEGER
  )`,
  'CREATE INDEX idx_outbox_operations_status ON outbox_operations (status, seq)',
  `INSERT INTO outbox_operations
     (op_id, owner_id, op_type, school_id, class_id, base_version, payload, status, created_at, attempts, next_attempt_at)
   SELECT
     op_id, owner_id, 'POINT_AWARD', school_id, class_id, NULL,
     json_object(
       'categoryId', category_id,
       'studentIds', CASE WHEN json_valid(student_ids) THEN json(student_ids) ELSE json('[]') END,
       'wholeClass', json(CASE WHEN whole_class = 1 THEN 'true' ELSE 'false' END),
       'points', points
     ),
     'PENDING', created_at, attempts, next_attempt_at
   FROM points_award_queue ORDER BY seq`,
  'DROP TABLE points_award_queue',
  'DROP TABLE sync_outbox',
];

interface Row {
  op_id: string;
  owner_id: string;
  op_type: string;
  school_id: string;
  class_id: string;
  base_version: number | null;
  payload: string;
  status: string;
  problem_status: number | null;
  problem_code: string | null;
  problem_detail: string | null;
  conflict: string | null;
  created_at: number;
  attempts: number;
  next_attempt_at: number;
  settled_at: number | null;
}

function parseJson(text: string | null): unknown {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const asRecord = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : []);

function readAttendance(value: unknown): AttendancePayload {
  const raw = asRecord(value);
  const records: AttendanceMark[] = (Array.isArray(raw.records) ? raw.records : []).flatMap((entry): AttendanceMark[] => {
    const record = asRecord(entry);
    if (typeof record.studentId !== 'string' || !isAttendanceStatus(record.status)) return [];
    return [
      {
        studentId: record.studentId,
        status: record.status,
        ...(typeof record.minutesLate === 'number' ? { minutesLate: record.minutesLate } : {}),
        ...(typeof record.expectedVersion === 'number' ? { expectedVersion: record.expectedVersion } : {}),
      },
    ];
  });
  return {
    date: typeof raw.date === 'string' ? raw.date : '',
    slot: typeof raw.slot === 'string' ? raw.slot : '',
    ...(typeof raw.subjectId === 'string' ? { subjectId: raw.subjectId } : {}),
    ...(typeof raw.groupId === 'string' ? { groupId: raw.groupId } : {}),
    records,
    ...(typeof raw.overrideReason === 'string' ? { overrideReason: raw.overrideReason } : {}),
  };
}

function readAward(value: unknown): AwardPayload {
  const raw = asRecord(value);
  return {
    categoryId: typeof raw.categoryId === 'string' ? raw.categoryId : '',
    studentIds: strings(raw.studentIds),
    wholeClass: raw.wholeClass === true || raw.wholeClass === 1,
    points: typeof raw.points === 'number' ? raw.points : null,
  };
}

function readConflict(value: unknown): ConflictState | undefined {
  const raw = asRecord(value);
  if (!Array.isArray(raw.entries)) return undefined;
  const entries: ConflictEntry[] = raw.entries.flatMap((entry): ConflictEntry[] => {
    const item = asRecord(entry);
    const stored = asRecord(item.stored);
    const submitted = asRecord(item.submitted);
    if (typeof item.studentId !== 'string' || !isAttendanceStatus(stored.status) || !isAttendanceStatus(submitted.status)) return [];
    return [
      {
        studentId: item.studentId,
        stored: {
          status: stored.status,
          minutesLate: typeof stored.minutesLate === 'number' ? stored.minutesLate : null,
          version: typeof stored.version === 'number' ? stored.version : null,
          recordedBy: typeof stored.recordedBy === 'string' ? stored.recordedBy : null,
        },
        submitted: { status: submitted.status, minutesLate: typeof submitted.minutesLate === 'number' ? submitted.minutesLate : null },
      },
    ];
  });
  return { sessionVersion: typeof raw.sessionVersion === 'number' ? raw.sessionVersion : null, entries };
}

const STATUSES: readonly OperationStatus[] = ['PENDING', 'CONFLICT', 'REJECTED', 'EXPIRED'];

function toItem(row: Row): QueuedOperation {
  const type: OperationType = row.op_type === 'ATTENDANCE_MARK' ? 'ATTENDANCE_MARK' : 'POINT_AWARD';
  const status = STATUSES.find((candidate) => candidate === row.status) ?? 'PENDING';
  const problem: OperationProblem | undefined =
    row.problem_code !== null
      ? { status: row.problem_status ?? 0, code: row.problem_code, detail: row.problem_detail ?? '' }
      : undefined;
  const conflict = row.conflict ? readConflict(parseJson(row.conflict)) : undefined;
  const base = {
    opId: row.op_id,
    ownerId: row.owner_id,
    schoolId: row.school_id,
    classId: row.class_id,
    baseVersion: row.base_version,
    createdAt: row.created_at,
    attempts: row.attempts,
    nextAttemptAt: row.next_attempt_at,
    status,
    ...(problem ? { problem } : {}),
    ...(conflict ? { conflict } : {}),
    ...(row.settled_at !== null ? { settledAt: row.settled_at } : {}),
  };
  const payload = parseJson(row.payload);
  return type === 'ATTENDANCE_MARK'
    ? { ...base, type, payload: readAttendance(payload) }
    : { ...base, type, payload: readAward(payload) };
}

/** What goes to the disk for a payload: the known fields only, so a note can never be written by accident. */
function payloadText(item: Pick<QueuedOperation, 'type' | 'payload'>): string {
  if (item.type === 'ATTENDANCE_MARK') return JSON.stringify(readAttendance(item.payload));
  return JSON.stringify(readAward(item.payload));
}

export function sqlOutboxStorage(db: () => Promise<OutboxDatabase>): OutboxStorage {
  return {
    async load() {
      const rows = await (await db()).getAllAsync<Row>('SELECT * FROM outbox_operations ORDER BY seq', []);
      return rows.map(toItem);
    },
    async insert(item) {
      await (
        await db()
      ).runAsync(
        `INSERT OR IGNORE INTO outbox_operations
          (op_id, owner_id, op_type, school_id, class_id, base_version, payload, status, created_at, attempts, next_attempt_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          item.opId,
          item.ownerId,
          item.type,
          item.schoolId,
          item.classId,
          item.baseVersion,
          payloadText(item),
          item.status,
          item.createdAt,
          item.attempts,
          item.nextAttemptAt,
        ],
      );
    },
    async updateAttempt(opId, patch) {
      await (
        await db()
      ).runAsync('UPDATE outbox_operations SET attempts = ?, next_attempt_at = ? WHERE op_id = ?', [patch.attempts, patch.nextAttemptAt, opId]);
    },
    async updatePayload(opId, patch) {
      await (
        await db()
      ).runAsync('UPDATE outbox_operations SET payload = ?, base_version = ? WHERE op_id = ?', [
        JSON.stringify(readAttendance(patch.payload)),
        patch.baseVersion,
        opId,
      ]);
    },
    async settle(opId, patch) {
      await (
        await db()
      ).runAsync(
        `UPDATE outbox_operations
            SET status = ?, problem_status = ?, problem_code = ?, problem_detail = ?, conflict = ?, settled_at = ?
          WHERE op_id = ?`,
        [
          patch.status,
          patch.problem?.status ?? null,
          patch.problem?.code ?? null,
          patch.problem?.detail ?? null,
          patch.conflict ? JSON.stringify(patch.conflict) : null,
          patch.settledAt,
          opId,
        ],
      );
    },
    async updateConflict(opId, conflict) {
      await (await db()).runAsync('UPDATE outbox_operations SET conflict = ? WHERE op_id = ?', [JSON.stringify(conflict), opId]);
    },
    async remove(opId) {
      await (await db()).runAsync('DELETE FROM outbox_operations WHERE op_id = ?', [opId]);
    },
    async clear() {
      await (await db()).runAsync('DELETE FROM outbox_operations', []);
    },
  };
}
