import { describe, expect, it } from 'vitest';

import { SyncEngine } from './engine';
import {
  APPLIED,
  MemoryStorage,
  REPLAYED,
  all,
  award,
  conflictState,
  mark,
  owner,
  recorder,
  rejected,
  results,
  roll,
  u,
  type Script,
} from './test-support';
import { backoffMs, DEFAULT_LIMITS, type BatchOutcome, type QueuedOperation, type SyncEvent, type SyncLimits } from './types';

function setup(options: { limits?: Partial<SyncLimits>; owner?: string | null; storage?: MemoryStorage; clock?: { now: number } } = {}) {
  const storage = options.storage ?? new MemoryStorage();
  const clock = options.clock ?? { now: 1_000_000 };
  const batches: string[][] = [];
  let script: Script = (batch) => all(batch, APPLIED);
  let currentOwner: string | null = options.owner === undefined ? owner : options.owner;
  const events: SyncEvent[] = [];
  const engine = new SyncEngine({
    storage,
    now: () => clock.now,
    getOwnerId: () => currentOwner,
    limits: { ...DEFAULT_LIMITS, ...options.limits },
    transport: async (batch) => {
      batches.push(batch.map((op) => op.opId));
      return script(batch, batches.length);
    },
  });
  engine.onEvent(recorder(events));
  return {
    engine,
    storage,
    clock,
    batches,
    events,
    sent: () => batches.flat(),
    script: (next: Script) => {
      script = next;
    },
    setOwner: (next: string | null) => {
      currentOwner = next;
    },
  };
}

const OFFLINE: BatchOutcome = { kind: 'retry', reason: 'network' };

describe('enqueue', () => {
  it('stores ids, marks and times only, never a note or anything else that sneaks in', async () => {
    const t = setup();
    const op = roll(1, [{ ...mark(1, 'LATE', { minutesLate: 10 }), note: 'fever', name: 'Abel' } as never]);
    expect(await t.engine.enqueue(op, { volatile: { notes: { [u(101)]: 'fever' } } })).toBe('queued');
    const [row] = t.storage.rows;
    expect(JSON.stringify(row)).not.toContain('fever');
    expect(JSON.stringify(row)).not.toContain('Abel');
    expect(row).toMatchObject({ opId: u(1), type: 'ATTENDANCE_MARK', status: 'PENDING', attempts: 0, createdAt: 1_000_000 });
    expect(Object.keys((row as QueuedOperation).payload)).toEqual(['date', 'slot', 'records']);
  });

  it('ignores a second enqueue of the same op id (never two actions for one tap)', async () => {
    const t = setup();
    expect(await t.engine.enqueue(award(1))).toBe('queued');
    expect(await t.engine.enqueue(award(1, { payload: { categoryId: u(902), studentIds: [u(102)], wholeClass: false, points: 2 } }))).toBe('duplicate');
    expect(t.engine.count()).toBe(1);
    expect(t.storage.rows[0]!.payload).toMatchObject({ studentIds: [u(101)] });
  });

  it('refuses invalid operations', async () => {
    const t = setup();
    const bad = [
      award(1, { opId: 'not-a-uuid' }),
      award(2, { payload: { categoryId: u(902), studentIds: [], wholeClass: false, points: null } }),
      award(3, { payload: { categoryId: u(902), studentIds: [u(101)], wholeClass: true, points: null } }),
      award(4, { payload: { categoryId: u(902), studentIds: [u(101)], wholeClass: false, points: 11 } }),
      award(5, { payload: { categoryId: u(902), studentIds: [u(101), u(101)], wholeClass: false, points: null } }),
      roll(6, []),
      roll(7, [mark(1), mark(1)]),
      roll(8, [mark(1, 'ABSENT', { minutesLate: 5 })]),
      roll(9, [mark(1, 'LATE', { minutesLate: 0 })]),
      roll(10, [mark(1)], { date: '2026-02-30' }),
      roll(11, [mark(1)], { slot: '' }),
      roll(12, [mark(1)], { baseVersion: -1 }),
    ];
    for (const op of bad) expect(await t.engine.enqueue(op)).toBe('invalid');
    expect(await t.engine.enqueue(award(20, { payload: { categoryId: u(902), studentIds: [], wholeClass: true, points: null } }))).toBe('queued');
    expect(t.engine.count()).toBe(1);
  });

  it('caps the size and says so instead of dropping silently', async () => {
    const t = setup({ limits: { maxItems: 2 } });
    expect(await t.engine.enqueue(award(1))).toBe('queued');
    expect(await t.engine.enqueue(award(2))).toBe('queued');
    expect(await t.engine.enqueue(award(3))).toBe('full');
    expect(t.engine.pending().map((item) => item.opId)).toEqual([u(1), u(2)]);
  });

  it('loads what an earlier run left behind', async () => {
    const t = setup();
    await t.storage.insert({ ...(award(1) as unknown as QueuedOperation), createdAt: 5, attempts: 2, nextAttemptAt: 0, status: 'PENDING' });
    await t.engine.init();
    expect(t.engine.pending().map((item) => item.opId)).toEqual([u(1)]);
  });

  it('gives views that stay the same object until something changes (for useSyncExternalStore)', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    const first = t.engine.pending();
    expect(t.engine.pending()).toBe(first);
    expect(t.engine.attention()).toBe(t.engine.attention());
    await t.engine.enqueue(award(2));
    expect(t.engine.pending()).not.toBe(first);
  });
});

describe('merging edits of one roll', () => {
  it('turns edits of the same roll that were not sent into one operation', async () => {
    const t = setup();
    expect(await t.engine.enqueue(roll(1, [mark(1, 'ABSENT', { expectedVersion: 2 })], { baseVersion: 4 }))).toBe('queued');
    expect(await t.engine.enqueue(roll(2, [mark(1, 'LATE', { minutesLate: 5, expectedVersion: 3 }), mark(2, 'PRESENT')], { baseVersion: 5 }))).toBe('merged');
    expect(t.engine.pending()).toHaveLength(1);
    const op = t.engine.pending()[0]!;
    expect(op.opId).toBe(u(1));
    // The later mark wins, the first view of the record (version 2) and of the roll (4) stay.
    expect(op.baseVersion).toBe(4);
    expect(op.type === 'ATTENDANCE_MARK' && op.payload.records).toEqual([
      { studentId: u(101), status: 'LATE', minutesLate: 5, expectedVersion: 2 },
      { studentId: u(102), status: 'PRESENT' },
    ]);
    expect(t.storage.rows[0]!.payload).toMatchObject({ records: [{ status: 'LATE' }, { status: 'PRESENT' }] });
  });

  it('keeps notes in memory only, merged per student', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)]), { volatile: { notes: { [u(101)]: 'one' } } });
    await t.engine.enqueue(roll(2, [mark(2)]), { volatile: { notes: { [u(102)]: 'two' } } });
    expect(t.engine.pending()[0]!.volatile?.notes).toEqual({ [u(101)]: 'one', [u(102)]: 'two' });
    expect(JSON.stringify(t.storage.rows)).not.toMatch(/one|two/);
  });

  it('never merges across rolls, teachers or into an operation that already went out', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)]));
    expect(await t.engine.enqueue(roll(2, [mark(1)], { date: '2026-11-24' }))).toBe('queued');
    expect(await t.engine.enqueue(roll(3, [mark(1)], { slot: 'AM' }))).toBe('queued');
    expect(await t.engine.enqueue(roll(4, [mark(1)], { classId: u(950) }))).toBe('queued');
    expect(await t.engine.enqueue({ ...roll(5, [mark(1)]), ownerId: 'someone-else' })).toBe('queued');
    // The first one was handed to the network once (and failed): the server may have applied it.
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    expect(await t.engine.enqueue(roll(6, [mark(2)]))).toBe('queued');
    expect(t.engine.pending()).toHaveLength(5); // the other teacher's row is discarded unsent
  });

  it('does not merge into an operation that is in the air', async () => {
    const t = setup();
    let release!: () => void;
    t.script(async (batch, call) => {
      if (call === 1) await new Promise<void>((resolve) => (release = resolve));
      return all(batch, APPLIED);
    });
    await t.engine.enqueue(roll(1, [mark(1)]));
    const flushing = t.engine.flush(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(await t.engine.enqueue(roll(2, [mark(2)]))).toBe('queued');
    release();
    await flushing;
    expect(t.sent()).toEqual([u(1), u(2)]);
  });

  it('does not merge after a restart into an operation that had been handed to the network', async () => {
    const storage = new MemoryStorage();
    const first = setup({ storage });
    await first.engine.enqueue(roll(1, [mark(1)]));
    first.script(() => OFFLINE);
    await first.engine.flush(true);
    const second = setup({ storage });
    expect(await second.engine.enqueue(roll(2, [mark(2)]))).toBe('queued');
    expect(second.engine.pending().map((item) => item.opId)).toEqual([u(1), u(2)]);
  });

  it('does not merge awards', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    expect(t.engine.count()).toBe(2);
  });
});

describe('flush', () => {
  it('sends in the order operations were queued, in one request, and removes applied ones', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]));
    await t.engine.enqueue(award(3));
    const summary = await t.engine.flush(true);
    expect(t.batches).toEqual([[u(1), u(2), u(3)]]);
    expect(summary).toMatchObject({ applied: 3, remaining: 0 });
    expect(t.engine.count()).toBe(0);
    expect(t.storage.rows).toEqual([]);
    expect(t.events.map((event) => event.type)).toEqual(['applied', 'applied', 'applied']);
  });

  it('batches at the cap and keeps going', async () => {
    const t = setup({ limits: { maxBatch: 2 } });
    for (let n = 1; n <= 5; n++) await t.engine.enqueue(award(n));
    await t.engine.flush(true);
    expect(t.batches).toEqual([[u(1), u(2)], [u(3), u(4)], [u(5)]]);
    expect(t.engine.count()).toBe(0);
  });

  it('batches by payload size too', async () => {
    const t = setup({ limits: { maxBatchBytes: 700 } });
    for (let n = 1; n <= 3; n++) await t.engine.enqueue(award(n));
    await t.engine.flush(true);
    expect(t.batches.length).toBeGreaterThan(1);
    expect(t.sent()).toEqual([u(1), u(2), u(3)]);
  });

  it('treats a DUPLICATE of an applied operation as done, never sent again', async () => {
    const t = setup();
    t.script((batch) => all(batch, REPLAYED));
    await t.engine.enqueue(award(1));
    await t.engine.flush(true);
    expect(t.events[0]).toMatchObject({ type: 'applied', replayed: true });
    await t.engine.flush(true);
    expect(t.batches).toHaveLength(1);
    expect(t.engine.count()).toBe(0);
  });

  it('keeps everything when offline, and sends the SAME operation ids once it is back (applied once)', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]));
    t.script(() => OFFLINE);
    expect(await t.engine.flush(true)).toMatchObject({ applied: 0, remaining: 2 });
    expect(t.batches).toHaveLength(1);
    expect(t.storage.rows.map((row) => row.opId)).toEqual([u(1), u(2)]);
    t.script((batch) => all(batch, APPLIED));
    t.clock.now += 60_000;
    await t.engine.flush(true);
    expect(t.batches[1]).toEqual([u(1), u(2)]);
    expect(t.engine.count()).toBe(0);
  });

  it('survives an app restart while offline: a new engine over the same storage sends the same ids', async () => {
    const storage = new MemoryStorage();
    const first = setup({ storage });
    await first.engine.enqueue(roll(1, [mark(1, 'ABSENT', { expectedVersion: 1 })], { baseVersion: 6 }));
    await first.engine.enqueue(award(2));
    first.script(() => OFFLINE);
    await first.engine.flush(true);

    const second = setup({ storage });
    await second.engine.init();
    expect(second.engine.pending().map((item) => item.opId)).toEqual([u(1), u(2)]);
    second.clock.now += 10 * 60_000;
    await second.engine.flush(true);
    expect(second.batches).toEqual([[u(1), u(2)]]);
    expect(second.batches[0]).toEqual([u(1), u(2)]);
    expect(storage.rows).toEqual([]);
  });

  it('respects the backoff for periodic retries and ignores it when forced', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    t.script((batch) => all(batch, APPLIED));
    await t.engine.flush(false);
    expect(t.batches).toHaveLength(1);
    t.clock.now += backoffMs(1) + 1;
    await t.engine.flush(false);
    expect(t.batches).toHaveLength(2);
    expect(t.engine.count()).toBe(0);
  });

  it('forced flushes skip the backoff', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    t.script((batch) => all(batch, APPLIED));
    await t.engine.flush(true);
    expect(t.engine.count()).toBe(0);
  });

  it('backs off exponentially, capped at five minutes', () => {
    expect([1, 2, 3, 4].map(backoffMs)).toEqual([2_000, 4_000, 8_000, 16_000]);
    expect(backoffMs(0)).toBe(2_000);
    expect(backoffMs(50)).toBe(300_000);
  });

  it('honours a Retry-After (429) longer than the backoff, for the whole batch', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    t.script(() => ({ kind: 'retry', reason: 'throttled', retryAfterMs: 90_000 }));
    await t.engine.flush(true);
    t.script((batch) => all(batch, APPLIED));
    t.clock.now += 60_000;
    await t.engine.flush(false);
    expect(t.batches).toHaveLength(1);
    t.clock.now += 31_000;
    await t.engine.flush(false);
    expect(t.engine.count()).toBe(0);
  });

  it('holds back later operations behind one that is not due yet (strict order)', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    await t.engine.enqueue(award(2));
    t.script((batch) => all(batch, APPLIED));
    await t.engine.flush(false);
    expect(t.batches).toHaveLength(1);
    t.clock.now += backoffMs(1) + 1;
    await t.engine.flush(false);
    expect(t.batches[1]).toEqual([u(1), u(2)]);
  });

  it('stops at a network failure so order is kept and nothing else is tried', async () => {
    const t = setup({ limits: { maxBatch: 1 } });
    for (let n = 1; n <= 3; n++) await t.engine.enqueue(award(n));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    expect(t.batches).toEqual([[u(1)]]);
    expect(t.engine.count()).toBe(3);
  });

  it('treats a transport that throws as offline', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script(() => {
      throw new Error('boom');
    });
    await expect(t.engine.flush(true)).resolves.toMatchObject({ remaining: 1 });
    expect(t.engine.count()).toBe(1);
  });

  it('an "offline" answer (nothing was sent) hands the attempt back, so the operation stays editable', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)]));
    t.script(() => ({ kind: 'retry', reason: 'offline' }));
    await t.engine.flush(true);
    expect(t.engine.pending()[0]!.attempts).toBe(0);
    expect(await t.engine.enqueue(roll(2, [mark(2)]))).toBe('merged');
  });

  it('keeps a transiently failed operation (retryable) and goes on once it is due, in order', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'retry', reason: 'server' }, [batch[1]!.opId]: APPLIED }));
    const summary = await t.engine.flush(true);
    expect(summary).toMatchObject({ applied: 1, remaining: 1 });
    expect(t.engine.pending().map((item) => item.opId)).toEqual([u(1)]);
    expect(t.engine.pending()[0]!.attempts).toBe(1);
    t.script((batch) => all(batch, APPLIED));
    t.clock.now += 5_000;
    await t.engine.flush(false);
    expect(t.engine.count()).toBe(0);
  });

  it('retries an operation the server did not answer for, with the same id', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    t.script((batch) => results({ [batch[1]!.opId]: APPLIED }));
    await t.engine.flush(true);
    expect(t.engine.pending().map((item) => item.opId)).toEqual([u(1)]);
  });

  it('partial failure: a rejected operation is kept for the teacher and does not block the next ones', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]));
    await t.engine.enqueue(award(3));
    t.script((batch) => results({ [batch[0]!.opId]: APPLIED, [batch[1]!.opId]: rejected('attendance.session_locked', 409), [batch[2]!.opId]: APPLIED }));
    const summary = await t.engine.flush(true);
    expect(summary).toMatchObject({ applied: 2, rejected: 1, remaining: 0 });
    expect(t.engine.attention()).toHaveLength(1);
    expect(t.engine.attention()[0]).toMatchObject({ opId: u(2), status: 'REJECTED', problem: { code: 'attendance.session_locked', status: 409, detail: 'detail of attendance.session_locked' } });
    const event = t.events.find((candidate) => candidate.type === 'rejected');
    expect(event).toMatchObject({ type: 'rejected', item: { opId: u(2) } });
    // Never sent again.
    await t.engine.flush(true);
    expect(t.batches).toHaveLength(1);
    expect(t.storage.rows).toHaveLength(1);
    expect(t.storage.rows[0]).toMatchObject({ status: 'REJECTED', problem: { code: 'attendance.session_locked' } });
  });

  it('a CONFLICT is kept with the clashing marks and not resent', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'PRESENT'), mark(2, 'PRESENT')]));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'conflict', version: 7, state: conflictState(1, 'ABSENT') } }));
    const summary = await t.engine.flush(true);
    expect(summary).toMatchObject({ conflicts: 1, applied: 0, remaining: 0 });
    const row = t.engine.attention()[0]!;
    expect(row.status).toBe('CONFLICT');
    expect(row.conflict).toEqual({
      sessionVersion: 7,
      entries: [
        {
          studentId: u(101),
          stored: { status: 'ABSENT', minutesLate: null, version: 2, recordedBy: u(555) },
          submitted: { status: 'PRESENT', minutesLate: null },
        },
      ],
    });
    expect(t.events[0]).toMatchObject({ type: 'conflict' });
    await t.engine.flush(true);
    expect(t.batches).toHaveLength(1);
  });

  it('an unreadable CONFLICT becomes a rejection the teacher can see instead of vanishing', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)]));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'conflict', version: 1, state: {} } }));
    await t.engine.flush(true);
    expect(t.engine.attention()[0]).toMatchObject({ status: 'REJECTED', problem: { code: 'sync.conflict_unreadable' } });
  });

  it('a request the server refuses for good is sent one operation at a time to find the bad one', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    await t.engine.enqueue(award(3));
    t.script((batch) => (batch.length > 1 ? { kind: 'refused', status: 400 } : batch[0]!.opId === u(2) ? { kind: 'refused', status: 400, code: 'validation.invalid', detail: 'bad' } : all(batch, APPLIED)));
    const summary = await t.engine.flush(true);
    expect(t.batches).toEqual([[u(1), u(2), u(3)], [u(1)], [u(2)], [u(3)]]);
    expect(summary).toMatchObject({ applied: 2, rejected: 1, remaining: 0 });
    expect(t.engine.attention()[0]).toMatchObject({ opId: u(2), problem: { status: 400, code: 'validation.invalid', detail: 'bad' } });
  });

  it('joins a flush that is running and looks again for operations queued meanwhile', async () => {
    const t = setup();
    let release!: () => void;
    let first = true;
    t.script(async (batch) => {
      if (first) {
        first = false;
        await new Promise<void>((resolve) => (release = resolve));
      }
      return all(batch, APPLIED);
    });
    await t.engine.enqueue(award(1));
    const one = t.engine.flush(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await t.engine.enqueue(award(2));
    const two = t.engine.flush(true);
    release();
    await Promise.all([one, two]);
    expect(t.sent()).toEqual([u(1), u(2)]);
    expect(t.engine.count()).toBe(0);
  });

  it('never sends one operation twice at the same time', async () => {
    const t = setup();
    let release!: () => void;
    let calls = 0;
    t.script(async (batch) => {
      calls++;
      await new Promise<void>((resolve) => (release = resolve));
      return all(batch, APPLIED);
    });
    await t.engine.enqueue(award(1));
    const flushes = [t.engine.flush(true), t.engine.flush(true), t.engine.flush(false)];
    await new Promise((resolve) => setTimeout(resolve, 0));
    release();
    await Promise.all(flushes);
    expect(calls).toBe(1);
    expect(t.sent()).toEqual([u(1)]);
  });

  it('emits the event before the row disappears, so a screen settles in one render', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    const seen: number[] = [];
    t.engine.onEvent(() => seen.push(t.engine.count()));
    await t.engine.flush(true);
    expect(seen).toEqual([1]);
    expect(t.engine.count()).toBe(0);
  });
});

describe('expiry', () => {
  it('gives up on a pending operation older than its type\'s cap, keeps it for the teacher, and never sends it', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]));
    t.clock.now += DEFAULT_LIMITS.maxAgeMs.POINT_AWARD + 1;
    const summary = await t.engine.flush(true);
    expect(summary).toMatchObject({ expired: 1, applied: 1 });
    expect(t.sent()).toEqual([u(2)]);
    expect(t.engine.attention()[0]).toMatchObject({ opId: u(1), status: 'EXPIRED' });
    expect(t.events.find((event) => event.type === 'expired')).toBeTruthy();
  });

  it('attendance waits a week, well inside the server\'s 30 days', async () => {
    expect(DEFAULT_LIMITS.maxAgeMs.ATTENDANCE_MARK).toBe(7 * 24 * 60 * 60 * 1000);
    expect(DEFAULT_LIMITS.maxAgeMs.ATTENDANCE_MARK).toBeLessThan(30 * 24 * 60 * 60 * 1000);
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)]));
    t.clock.now += 6 * 24 * 60 * 60 * 1000;
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    expect(t.engine.count()).toBe(1);
    t.clock.now += 2 * 24 * 60 * 60 * 1000;
    await t.engine.flush(true);
    expect(t.engine.attention()[0]!.status).toBe('EXPIRED');
  });

  it('expires on the next flush even when the operation was still failing', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    t.clock.now += DEFAULT_LIMITS.maxAgeMs.POINT_AWARD + 1;
    await t.engine.flush(true);
    expect(t.engine.count()).toBe(0);
    expect(t.engine.attention()[0]!.status).toBe('EXPIRED');
  });

  it('forgets what waited for the teacher after a week', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    t.script((batch) => results({ [batch[0]!.opId]: rejected() }));
    await t.engine.flush(true);
    expect(t.engine.attention()).toHaveLength(1);
    t.clock.now += DEFAULT_LIMITS.attentionRetentionMs + 1;
    await t.engine.flush(true);
    expect(t.engine.attention()).toHaveLength(0);
    expect(t.storage.rows).toEqual([]);
  });

  it('refuses a row that is not a valid operation (an older build, a damaged database) without sending it', async () => {
    const t = setup();
    await t.storage.insert({ ...(award(1, { opId: 'legacy-id' }) as unknown as QueuedOperation), createdAt: 1_000_000, attempts: 0, nextAttemptAt: 0, status: 'PENDING' });
    await t.engine.flush(true);
    expect(t.batches).toEqual([]);
    expect(t.engine.attention()[0]).toMatchObject({ status: 'REJECTED', problem: { code: 'sync.invalid_operation' } });
  });
});

describe('cancel (undo before it was sent)', () => {
  it('removes a waiting operation so it is never sent', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(award(2));
    expect(await t.engine.cancel(u(1))).toBe('cancelled');
    await t.engine.flush(true);
    expect(t.sent()).toEqual([u(2)]);
  });

  it('reports an unknown operation', async () => {
    const t = setup();
    expect(await t.engine.cancel(u(9))).toBe('not-found');
  });

  it('does not remove one that is in the air, or one that already went out once (it may have been applied)', async () => {
    const t = setup();
    let release!: () => void;
    t.script(async (batch) => {
      await new Promise<void>((resolve) => (release = resolve));
      return all(batch, APPLIED);
    });
    await t.engine.enqueue(award(1));
    const flushing = t.engine.flush(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(await t.engine.cancel(u(1))).toBe('in-flight');
    release();
    await flushing;

    await t.engine.enqueue(award(2));
    t.script(() => OFFLINE);
    await t.engine.flush(true);
    expect(await t.engine.cancel(u(2))).toBe('in-flight');
  });
});

describe('what waits for the teacher', () => {
  async function conflicted() {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'PRESENT')]));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'conflict', version: 7, state: conflictState() } }));
    await t.engine.flush(true);
    return t;
  }

  it('dismiss forgets a conflict, a rejection or an expired operation, but not a pending one', async () => {
    const t = await conflicted();
    await t.engine.enqueue(award(2));
    await t.engine.dismiss(u(2));
    expect(t.engine.pending()).toHaveLength(1);
    await t.engine.dismiss(u(1));
    expect(t.engine.attention()).toHaveLength(0);
    expect(t.storage.rows.map((row) => row.opId)).toEqual([u(2)]);
  });

  it('updateConflict keeps the unresolved entries and forgets the row when none is left', async () => {
    const t = await conflicted();
    const row = t.engine.attention()[0]!;
    const entry = row.conflict!.entries[0]!;
    await t.engine.updateConflict(u(1), { sessionVersion: 7, entries: [entry, { ...entry, studentId: u(102) }] });
    expect(t.engine.attention()[0]!.conflict!.entries).toHaveLength(2);
    expect(t.storage.rows[0]!.conflict!.entries).toHaveLength(2);
    await t.engine.updateConflict(u(1), { sessionVersion: 7, entries: [] });
    expect(t.engine.attention()).toHaveLength(0);
    expect(t.storage.rows).toEqual([]);
  });

  it('survives a restart: a conflict is still there after the app was closed', async () => {
    const t = await conflicted();
    const second = setup({ storage: t.storage });
    await second.engine.init();
    expect(second.engine.attention()[0]).toMatchObject({ opId: u(1), status: 'CONFLICT' });
    expect(second.engine.pending()).toHaveLength(0);
  });
});

describe('owner and sign-out', () => {
  it('sends nothing while nobody is signed in', async () => {
    const t = setup({ owner: null });
    await t.engine.enqueue(award(1));
    await t.engine.flush(true);
    expect(t.batches).toEqual([]);
    expect(t.engine.count()).toBe(1);
  });

  it('never sends one teacher\'s operations under another teacher\'s session, whatever their status', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]));
    t.script((batch) => results({ [batch[0]!.opId]: rejected() }));
    await t.engine.flush(true);
    await t.engine.enqueue(award(3));
    t.script((batch) => all(batch, APPLIED));
    t.setOwner('teacher-2');
    await t.engine.flush(true);
    expect(t.sent()).not.toContain(u(3));
    expect(t.engine.pending()).toHaveLength(0);
    expect(t.engine.attention()).toHaveLength(0);
    expect(t.storage.rows).toEqual([]);
    expect(t.events.filter((event) => event.type === 'discarded')).toHaveLength(3);
  });

  it('clear() wipes memory and storage', async () => {
    const t = setup();
    await t.engine.enqueue(award(1));
    await t.engine.enqueue(roll(2, [mark(1)]), { volatile: { notes: { [u(101)]: 'private' } } });
    await t.engine.clear();
    expect(t.engine.count()).toBe(0);
    expect(t.storage.rows).toEqual([]);
    await t.engine.flush(true);
    expect(t.batches).toEqual([]);
  });

  it('a send that was in the air at sign-out does not touch the new state or raise events', async () => {
    const t = setup();
    let release!: () => void;
    t.script(async (batch) => {
      await new Promise<void>((resolve) => (release = resolve));
      return all(batch, APPLIED);
    });
    await t.engine.enqueue(award(1));
    const flushing = t.engine.flush(true);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await t.engine.clear();
    await t.engine.enqueue(award(2, { ownerId: 'teacher-2' }));
    release();
    await flushing;
    expect(t.events).toEqual([]);
    expect(t.storage.rows.map((row) => row.opId)).toEqual([u(2)]);
  });

  it('notifies subscribers when the rows change', async () => {
    const t = setup();
    let calls = 0;
    t.engine.subscribe(() => calls++);
    await t.engine.enqueue(award(1));
    expect(calls).toBeGreaterThan(0);
    const before = calls;
    await t.engine.flush(true);
    expect(calls).toBeGreaterThan(before);
  });
});

describe('the exit criterion, as the engine sees it', () => {
  it('airplane mode: marks attendance and awards points, restarts, reconnects, and everything lands once, with a colleague\'s change surfaced', async () => {
    const storage = new MemoryStorage();
    const offline = setup({ storage });
    // Marks (edited twice, merged) and an award, queued with no connection.
    await offline.engine.enqueue(roll(1, [mark(1, 'ABSENT'), mark(2, 'PRESENT')], { baseVersion: 3 }));
    await offline.engine.enqueue(roll(2, [mark(1, 'LATE', { minutesLate: 12 })], { baseVersion: 3 }));
    await offline.engine.enqueue(award(3));
    offline.script(() => OFFLINE);
    await offline.engine.flush(true);
    expect(offline.engine.pending().map((op) => op.opId)).toEqual([u(1), u(3)]);

    // The app is closed and opened again, still offline.
    const restarted = setup({ storage, clock: { now: offline.clock.now + 3_600_000 } });
    restarted.script(() => OFFLINE);
    await restarted.engine.flush(true);
    expect(restarted.engine.count()).toBe(2);

    // Back online: a colleague changed Abel meanwhile, so that mark clashes; the award goes through.
    restarted.script((batch) =>
      results({
        [batch[0]!.opId]: { kind: 'conflict', version: 9, state: conflictState(1, 'ABSENT') },
        [batch[1]!.opId]: APPLIED,
      }),
    );
    restarted.clock.now += 10 * 60_000;
    const summary = await restarted.engine.flush(true);
    expect(summary).toMatchObject({ applied: 1, conflicts: 1, remaining: 0 });
    expect(restarted.batches.at(-1)).toEqual([u(1), u(3)]);
    expect(restarted.engine.attention()[0]).toMatchObject({ opId: u(1), status: 'CONFLICT' });
    expect(restarted.engine.count()).toBe(0);
  });
});
