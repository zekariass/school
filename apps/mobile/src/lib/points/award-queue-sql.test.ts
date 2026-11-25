/// <reference types="node" />
import { DatabaseSync } from 'node:sqlite';

import { describe, expect, it } from 'vitest';

import { MIGRATIONS } from '@/lib/db/migrations';

import { AwardQueue, type SendOutcome } from './award-queue';
import { AWARD_QUEUE_STATEMENTS, sqlAwardQueueStorage, type QueueDatabase } from './award-queue-sql';

/** The queue's SQL against a real SQLite (Node's built-in), through the same narrow interface the app uses. */
function memoryDatabase() {
  const raw = new DatabaseSync(':memory:');
  for (const statement of AWARD_QUEUE_STATEMENTS) raw.exec(statement);
  const db: QueueDatabase = {
    async runAsync(sql, params) {
      raw.prepare(sql).run(...params);
    },
    async getAllAsync<T>(sql: string, params: (string | number | null)[]) {
      return raw.prepare(sql).all(...params) as T[];
    },
  };
  return { raw, db };
}

const item = (opId: string, overrides = {}) => ({
  opId,
  ownerId: 'u1',
  schoolId: 'sc',
  classId: 'cl',
  categoryId: 'ca',
  studentIds: ['s1', 's2'],
  wholeClass: false,
  points: 2,
  createdAt: 100,
  attempts: 0,
  nextAttemptAt: 0,
  ...overrides,
});

describe('award queue table', () => {
  it('is migration 2 and holds ids, numbers and times only', () => {
    expect(MIGRATIONS[1]?.name).toBe('create_points_award_queue');
    const { raw } = memoryDatabase();
    const columns = (raw.prepare('PRAGMA table_info(points_award_queue)').all() as { name: string }[]).map((c) => c.name);
    expect(columns).toEqual([
      'seq',
      'op_id',
      'owner_id',
      'school_id',
      'class_id',
      'category_id',
      'student_ids',
      'whole_class',
      'points',
      'created_at',
      'attempts',
      'next_attempt_at',
    ]);
    // No place for a note, a name or a token.
    expect(columns.some((name) => /note|name|token|text/.test(name))).toBe(false);
  });
});

describe('sqlAwardQueueStorage', () => {
  it('round-trips an item and keeps the insertion order', async () => {
    const { db } = memoryDatabase();
    const storage = sqlAwardQueueStorage(async () => db);
    await storage.insert(item('b'));
    await storage.insert(item('a', { wholeClass: true, studentIds: [], points: null }));
    const rows = await storage.load();
    expect(rows.map((row) => row.opId)).toEqual(['b', 'a']);
    expect(rows[0]).toEqual(item('b'));
    expect(rows[1]).toMatchObject({ wholeClass: true, studentIds: [], points: null });
  });

  it('ignores a duplicate op id, updates attempts, removes and clears', async () => {
    const { db } = memoryDatabase();
    const storage = sqlAwardQueueStorage(async () => db);
    await storage.insert(item('a'));
    await storage.insert(item('a', { points: 9 }));
    expect((await storage.load())[0]!.points).toBe(2);
    await storage.update('a', { attempts: 3, nextAttemptAt: 5000 });
    expect((await storage.load())[0]).toMatchObject({ attempts: 3, nextAttemptAt: 5000 });
    await storage.insert(item('b'));
    await storage.remove('a');
    expect((await storage.load()).map((row) => row.opId)).toEqual(['b']);
    await storage.clear();
    expect(await storage.load()).toEqual([]);
  });

  it('survives an app restart: a new queue over the same database sends what was waiting, with the same op id', async () => {
    const { db } = memoryDatabase();
    const storage = sqlAwardQueueStorage(async () => db);
    const sent: string[] = [];
    const offline = new AwardQueue({
      storage,
      now: () => 1_000,
      getOwnerId: () => 'u1',
      transport: async () => ({ kind: 'retry', reason: 'network' }),
    });
    await offline.enqueue({ opId: 'op-1', ownerId: 'u1', schoolId: 'sc', classId: 'cl', categoryId: 'ca', studentIds: ['s1'], wholeClass: false, points: null });
    await offline.flush();
    expect(offline.pending()[0]!.attempts).toBe(1);

    const ok: SendOutcome = { kind: 'confirmed', replayed: false, confirmation: { batchId: null, undoableUntil: null, transactionIds: [] } };
    const restarted = new AwardQueue({
      storage: sqlAwardQueueStorage(async () => db),
      now: () => 2_000,
      getOwnerId: () => 'u1',
      transport: async (queued) => {
        sent.push(queued.opId);
        return ok;
      },
    });
    await restarted.init();
    expect(restarted.count()).toBe(1);
    await restarted.flush(true);
    expect(sent).toEqual(['op-1']);
    expect(await storage.load()).toEqual([]);
  });

  it('sign-out leaves nothing behind', async () => {
    const { db } = memoryDatabase();
    const storage = sqlAwardQueueStorage(async () => db);
    const queue = new AwardQueue({ storage, now: () => 1, getOwnerId: () => 'u1', transport: async () => ({ kind: 'retry', reason: 'network' }) });
    await queue.enqueue({ opId: 'op-1', ownerId: 'u1', schoolId: 'sc', classId: 'cl', categoryId: 'ca', studentIds: ['s1'], wholeClass: false, points: null });
    await queue.clear();
    expect(await storage.load()).toEqual([]);
  });
});
