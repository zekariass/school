/// <reference types="node" />
import { DatabaseSync } from 'node:sqlite';

import { describe, expect, it } from 'vitest';

import { MIGRATIONS, runMigrations, type MigrationDatabase } from '@/lib/db/migrations';

import { SyncEngine } from './engine';
import { sqlOutboxStorage, type OutboxDatabase } from './outbox-sql';
import { APPLIED, all, award, conflictState, mark, results, roll, u } from './test-support';
import type { QueuedOperation } from './types';

/** The app's SQL against a real SQLite (Node's built-in), through the same narrow interfaces the app uses. */
function memoryDatabase() {
  const raw = new DatabaseSync(':memory:');
  const db: OutboxDatabase & MigrationDatabase = {
    async runAsync(sql, params) {
      raw.prepare(sql).run(...params);
    },
    async getAllAsync<T>(sql: string, params: (string | number | null)[]) {
      return raw.prepare(sql).all(...params) as T[];
    },
    async execAsync(sql) {
      raw.exec(sql);
    },
    async getFirstAsync<T>(sql: string) {
      return (raw.prepare(sql).get() as T | undefined) ?? null;
    },
    async withTransactionAsync(task) {
      raw.exec('BEGIN');
      try {
        await task();
        raw.exec('COMMIT');
      } catch (error) {
        raw.exec('ROLLBACK');
        throw error;
      }
    },
  };
  return { raw, db };
}

const op = (type: 'ATTENDANCE_MARK' | 'POINT_AWARD', n: number, overrides: Partial<QueuedOperation> = {}): QueuedOperation =>
  ({
    ...(type === 'ATTENDANCE_MARK' ? roll(n, [mark(1, 'LATE', { minutesLate: 7, expectedVersion: 2 })]) : award(n)),
    createdAt: 100,
    attempts: 0,
    nextAttemptAt: 0,
    status: 'PENDING',
    ...overrides,
  }) as QueuedOperation;

describe('outbox table', () => {
  it('is migration 3 and holds ids, statuses, numbers and times only', async () => {
    expect(MIGRATIONS[2]?.name).toBe('create_outbox_operations');
    const { raw, db } = memoryDatabase();
    await runMigrations(db);
    const columns = (raw.prepare('PRAGMA table_info(outbox_operations)').all() as { name: string }[]).map((c) => c.name);
    expect(columns).toEqual([
      'seq',
      'op_id',
      'owner_id',
      'op_type',
      'school_id',
      'class_id',
      'base_version',
      'payload',
      'status',
      'problem_status',
      'problem_code',
      'problem_detail',
      'conflict',
      'created_at',
      'attempts',
      'next_attempt_at',
      'settled_at',
    ]);
    // No place for a note, a name or a token, and the old tables are gone.
    const tables = (raw.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all() as { name: string }[]).map((t) => t.name);
    expect(tables).toContain('outbox_operations');
    expect(tables).not.toContain('points_award_queue');
    expect(tables).not.toContain('sync_outbox');
  });
});

describe('sqlOutboxStorage', () => {
  async function storage() {
    const { raw, db } = memoryDatabase();
    await runMigrations(db);
    return { raw, store: sqlOutboxStorage(async () => db) };
  }

  it('round-trips operations in insertion order, with their marks', async () => {
    const { store } = await storage();
    await store.insert(op('ATTENDANCE_MARK', 1, { baseVersion: 6 }));
    await store.insert(op('POINT_AWARD', 2, { attempts: 3, nextAttemptAt: 99 }));
    const rows = await store.load();
    expect(rows.map((row) => row.opId)).toEqual([u(1), u(2)]);
    expect(rows[0]).toMatchObject({
      type: 'ATTENDANCE_MARK',
      baseVersion: 6,
      status: 'PENDING',
      payload: { date: '2026-11-25', slot: 'DAY', records: [{ studentId: u(101), status: 'LATE', minutesLate: 7, expectedVersion: 2 }] },
    });
    expect(rows[1]).toMatchObject({ type: 'POINT_AWARD', attempts: 3, nextAttemptAt: 99, payload: { wholeClass: false, points: null } });
  });

  it('writes the known fields of a payload only: a note can not reach the disk', async () => {
    const { raw, store } = await storage();
    const sneaky = op('ATTENDANCE_MARK', 1);
    (sneaky.payload as unknown as { records: Record<string, unknown>[] }).records[0]!.note = 'Abel has a fever';
    (sneaky.payload as unknown as Record<string, unknown>).name = 'Abel';
    await store.insert(sneaky);
    const text = JSON.stringify(raw.prepare('SELECT * FROM outbox_operations').all());
    expect(text).not.toContain('fever');
    expect(text).not.toContain('Abel');
  });

  it('ignores a duplicate op id, updates attempts and merged payloads, removes and clears', async () => {
    const { store } = await storage();
    await store.insert(op('ATTENDANCE_MARK', 1));
    await store.insert(op('ATTENDANCE_MARK', 1, { baseVersion: 9 }));
    expect(await store.load()).toHaveLength(1);
    await store.updateAttempt(u(1), { attempts: 2, nextAttemptAt: 5000 });
    await store.updatePayload(u(1), { payload: { date: '2026-11-25', slot: 'DAY', records: [{ studentId: u(102), status: 'ABSENT' }] }, baseVersion: 2 });
    expect((await store.load())[0]).toMatchObject({ attempts: 2, nextAttemptAt: 5000, baseVersion: 2, payload: { records: [{ studentId: u(102), status: 'ABSENT' }] } });
    await store.insert(op('POINT_AWARD', 2));
    await store.remove(u(1));
    expect((await store.load()).map((row) => row.opId)).toEqual([u(2)]);
    await store.clear();
    expect(await store.load()).toEqual([]);
  });

  it('keeps a rejection, an expiry and a conflict across a restart', async () => {
    const { store } = await storage();
    await store.insert(op('ATTENDANCE_MARK', 1));
    await store.insert(op('ATTENDANCE_MARK', 2));
    await store.settle(u(1), { status: 'REJECTED', problem: { status: 409, code: 'attendance.session_locked', detail: 'The roll is locked.' }, conflict: null, settledAt: 500 });
    const conflict = { sessionVersion: 7, entries: [{ studentId: u(101), stored: { status: 'ABSENT' as const, minutesLate: null, version: 2, recordedBy: u(5) }, submitted: { status: 'PRESENT' as const, minutesLate: null } }] };
    await store.settle(u(2), { status: 'CONFLICT', problem: null, conflict, settledAt: 600 });
    const [rejected, conflicted] = await store.load();
    expect(rejected).toMatchObject({ status: 'REJECTED', settledAt: 500, problem: { status: 409, code: 'attendance.session_locked', detail: 'The roll is locked.' } });
    expect(conflicted).toMatchObject({ status: 'CONFLICT', conflict });
    await store.updateConflict(u(2), { sessionVersion: 7, entries: [] });
    expect((await store.load())[1]!.conflict).toEqual({ sessionVersion: 7, entries: [] });
  });

  it('a restarted engine over SQLite sends the same operations with the same ids', async () => {
    const { db } = memoryDatabase();
    await runMigrations(db);
    const store = sqlOutboxStorage(async () => db);
    const clock = { now: 1_000 };
    const sent: string[][] = [];
    const make = (offline: boolean) =>
      new SyncEngine({
        storage: store,
        now: () => clock.now,
        getOwnerId: () => 'teacher-1',
        transport: async (batch) => {
          sent.push(batch.map((item) => item.opId));
          return offline ? { kind: 'retry', reason: 'network' } : all(batch, APPLIED);
        },
      });
    const first = make(true);
    await first.enqueue(roll(1, [mark(1)]));
    await first.enqueue(award(2));
    await first.flush(true);
    const second = make(false);
    clock.now += 60_000;
    await second.flush(true);
    expect(sent).toEqual([[u(1), u(2)], [u(1), u(2)]]);
    expect(await store.load()).toEqual([]);
  });

  it('a conflict row is read back by a new engine', async () => {
    const { db } = memoryDatabase();
    await runMigrations(db);
    const store = sqlOutboxStorage(async () => db);
    const make = () =>
      new SyncEngine({
        storage: store,
        now: () => 1_000,
        getOwnerId: () => 'teacher-1',
        transport: async (batch) => results({ [batch[0]!.opId]: { kind: 'conflict', version: 7, state: conflictState() } }),
      });
    const first = make();
    await first.enqueue(roll(1, [mark(1, 'PRESENT')]));
    await first.flush(true);
    const second = make();
    await second.init();
    expect(second.attention()[0]).toMatchObject({ opId: u(1), status: 'CONFLICT', conflict: { entries: [{ studentId: u(101) }] } });
  });
});

describe('migration from the M6c award queue', () => {
  const M6C_ROW = `INSERT INTO points_award_queue
    (op_id, owner_id, school_id, class_id, category_id, student_ids, whole_class, points, created_at, attempts, next_attempt_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`;

  async function upgraded() {
    const { raw, db } = memoryDatabase();
    // An installed M6c build: migrations 1 and 2 only, with awards waiting.
    await runMigrations(db, MIGRATIONS.slice(0, 2));
    raw.prepare(M6C_ROW).run(u(1), 'teacher-1', u(900), u(901), u(902), JSON.stringify([u(101), u(102)]), 0, 3, 111, 2, 5000);
    raw.prepare(M6C_ROW).run(u(2), 'teacher-1', u(900), u(901), u(902), '[]', 1, null, 222, 0, 0);
    raw.prepare(M6C_ROW).run(u(3), 'teacher-2', u(900), u(902), u(902), JSON.stringify([u(103)]), 0, 1, 333, 1, 0);
    raw.prepare(M6C_ROW).run('legacy-bad-id', 'teacher-1', u(900), u(901), u(902), 'not json', 0, 2, 444, 0, 0);
    expect(await runMigrations(db)).toBe(MIGRATIONS.length);
    return { raw, db };
  }

  it('keeps every queued award, with its id, owner, points, age and attempts', async () => {
    const { db } = await upgraded();
    const rows = await sqlOutboxStorage(async () => db).load();
    expect(rows.map((row) => row.opId)).toEqual([u(1), u(2), u(3), 'legacy-bad-id']);
    expect(rows[0]).toEqual({
      type: 'POINT_AWARD',
      opId: u(1),
      ownerId: 'teacher-1',
      schoolId: u(900),
      classId: u(901),
      baseVersion: null,
      createdAt: 111,
      attempts: 2,
      nextAttemptAt: 5000,
      status: 'PENDING',
      payload: { categoryId: u(902), studentIds: [u(101), u(102)], wholeClass: false, points: 3 },
    });
    expect(rows[1]).toMatchObject({ opId: u(2), payload: { studentIds: [], wholeClass: true, points: null } });
    expect(rows[2]).toMatchObject({ ownerId: 'teacher-2' });
  });

  it('sends each migrated award once, as a POINT_AWARD with the old id, and refuses a row it cannot read', async () => {
    const { db } = await upgraded();
    const sent: string[] = [];
    const engine = new SyncEngine({
      storage: sqlOutboxStorage(async () => db),
      now: () => 1_000,
      getOwnerId: () => 'teacher-1',
      transport: async (batch) => {
        sent.push(...batch.map((item) => `${item.type}:${item.opId}`));
        return all(batch, APPLIED);
      },
    });
    await engine.flush(true);
    expect(sent).toEqual([`POINT_AWARD:${u(1)}`, `POINT_AWARD:${u(2)}`]);
    // The other teacher's award was discarded unsent, the unreadable one is kept for the teacher to see.
    expect(engine.pending()).toEqual([]);
    expect(engine.attention().map((row) => [row.opId, row.status])).toEqual([['legacy-bad-id', 'REJECTED']]);
    await engine.flush(true);
    expect(sent).toHaveLength(2);
  });

  it('a clean install runs all three migrations', async () => {
    const { raw, db } = memoryDatabase();
    await runMigrations(db);
    expect(raw.prepare('PRAGMA user_version').get()).toEqual({ user_version: MIGRATIONS.length });
  });
});
