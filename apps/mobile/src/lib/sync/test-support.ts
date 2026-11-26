/** Helpers shared by the outbox tests (not imported by the app). */
import type { OutboxStorage } from './engine';
import type {
  AttendanceMark,
  BatchOutcome,
  NewOperation,
  OperationResult,
  QueuedOperation,
  SyncEvent,
} from './types';

export const u = (n: number): string => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

export class MemoryStorage implements OutboxStorage {
  rows: QueuedOperation[] = [];
  async load() {
    return this.rows.map((row) => ({ ...row }));
  }
  async insert(item: QueuedOperation) {
    // What the SQL storage keeps: never the memory-only part.
    const { volatile: _volatile, ...rest } = item;
    if (!this.rows.some((row) => row.opId === item.opId)) this.rows.push(rest as QueuedOperation);
  }
  async updateAttempt(opId: string, patch: { attempts: number; nextAttemptAt: number }) {
    this.rows = this.rows.map((row) => (row.opId === opId ? { ...row, ...patch } : row));
  }
  async updatePayload(opId: string, patch: { payload: QueuedOperation['payload']; baseVersion: number | null }) {
    this.rows = this.rows.map((row) => (row.opId === opId ? ({ ...row, ...patch } as QueuedOperation) : row));
  }
  async settle(
    opId: string,
    patch: { status: 'CONFLICT' | 'REJECTED' | 'EXPIRED'; problem: QueuedOperation['problem'] | null; conflict: QueuedOperation['conflict'] | null; settledAt: number },
  ) {
    this.rows = this.rows.map((row) =>
      row.opId === opId
        ? ({ ...row, status: patch.status, settledAt: patch.settledAt, ...(patch.problem ? { problem: patch.problem } : {}), ...(patch.conflict ? { conflict: patch.conflict } : {}) } as QueuedOperation)
        : row,
    );
  }
  async updateConflict(opId: string, conflict: NonNullable<QueuedOperation['conflict']>) {
    this.rows = this.rows.map((row) => (row.opId === opId ? { ...row, conflict } : row));
  }
  async remove(opId: string) {
    this.rows = this.rows.filter((row) => row.opId !== opId);
  }
  async clear() {
    this.rows = [];
  }
}

export const owner = 'teacher-1';
export const SCHOOL = u(900);
export const CLASS = u(901);
export const CATEGORY = u(902);

export const mark = (n: number, status: AttendanceMark['status'] = 'ABSENT', extra: Partial<AttendanceMark> = {}): AttendanceMark => ({
  studentId: u(100 + n),
  status,
  ...extra,
});

export function award(n: number, overrides: Partial<Extract<NewOperation, { type: 'POINT_AWARD' }>> = {}): NewOperation {
  return {
    type: 'POINT_AWARD',
    opId: u(n),
    ownerId: owner,
    schoolId: SCHOOL,
    classId: CLASS,
    baseVersion: null,
    payload: { categoryId: CATEGORY, studentIds: [u(101)], wholeClass: false, points: null },
    ...overrides,
  } as NewOperation;
}

export function roll(
  n: number,
  records: AttendanceMark[] = [mark(1)],
  overrides: { baseVersion?: number | null; date?: string; slot?: string; classId?: string; overrideReason?: string } = {},
): NewOperation {
  return {
    type: 'ATTENDANCE_MARK',
    opId: u(n),
    ownerId: owner,
    schoolId: SCHOOL,
    classId: overrides.classId ?? CLASS,
    baseVersion: overrides.baseVersion === undefined ? 3 : overrides.baseVersion,
    payload: {
      date: overrides.date ?? '2026-11-25',
      slot: overrides.slot ?? 'DAY',
      records,
      ...(overrides.overrideReason ? { overrideReason: overrides.overrideReason } : {}),
    },
  };
}

export const APPLIED: OperationResult = { kind: 'applied', replayed: false, version: 1, state: {} };
export const REPLAYED: OperationResult = { kind: 'applied', replayed: true, version: 1, state: {} };

/** A server state with one clashing mark (`AttendanceWriteResult`). */
export function conflictState(studentN = 1, stored: 'PRESENT' | 'ABSENT' = 'ABSENT'): unknown {
  return {
    sessionVersion: 7,
    outcomes: [
      {
        studentId: u(100 + studentN),
        outcome: 'CONFLICT',
        record: { status: stored, version: 2, recordedBy: u(555), minutesLate: null },
        submitted: { status: stored === 'ABSENT' ? 'PRESENT' : 'ABSENT' },
      },
    ],
  };
}

export const rejected = (code = 'class.not_found', status = 404): OperationResult => ({
  kind: 'rejected',
  problem: { status, code, detail: `detail of ${code}` },
});

export const results = (entries: Record<string, OperationResult>): BatchOutcome => ({ kind: 'results', results: new Map(Object.entries(entries)) });

/** Every operation of the batch gets the same result. */
export const all = (batch: readonly QueuedOperation[], result: OperationResult): BatchOutcome =>
  results(Object.fromEntries(batch.map((op) => [op.opId, result])));

export type Script = (batch: readonly QueuedOperation[], call: number) => BatchOutcome | Promise<BatchOutcome>;

export function recorder(events: SyncEvent[]) {
  return (event: SyncEvent) => events.push(event);
}
