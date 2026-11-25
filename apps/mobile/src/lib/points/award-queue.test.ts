import { describe, expect, it } from 'vitest';

import {
  AwardQueue,
  backoffMs,
  DEFAULT_LIMITS,
  type AwardQueueStorage,
  type NewAward,
  type QueueEvent,
  type QueuedAward,
  type SendOutcome,
} from './award-queue';

class MemoryStorage implements AwardQueueStorage {
  rows: QueuedAward[] = [];
  async load() {
    return this.rows.map((row) => ({ ...row }));
  }
  async insert(item: QueuedAward) {
    if (!this.rows.some((row) => row.opId === item.opId)) this.rows.push({ ...item });
  }
  async update(opId: string, patch: { attempts: number; nextAttemptAt: number }) {
    this.rows = this.rows.map((row) => (row.opId === opId ? { ...row, ...patch } : row));
  }
  async remove(opId: string) {
    this.rows = this.rows.filter((row) => row.opId !== opId);
  }
  async clear() {
    this.rows = [];
  }
}

const OK: SendOutcome = { kind: 'confirmed', replayed: false, confirmation: { batchId: 'b1', undoableUntil: null, transactionIds: ['t1'] } };
const REPLAY: SendOutcome = { kind: 'confirmed', replayed: true, confirmation: { batchId: 'b1', undoableUntil: null, transactionIds: ['t1'] } };
const OFFLINE: SendOutcome = { kind: 'retry', reason: 'network' };
const SERVER_ERROR: SendOutcome = { kind: 'retry', reason: 'server' };

function award(opId: string, overrides: Partial<NewAward> = {}): NewAward {
  return {
    opId,
    ownerId: 'teacher-1',
    schoolId: 'school-1',
    classId: 'class-1',
    categoryId: 'cat-1',
    studentIds: ['s1'],
    wholeClass: false,
    points: null,
    ...overrides,
  };
}

function setup(options: { limits?: { maxItems: number; maxAgeMs: number }; owner?: string | null } = {}) {
  const storage = new MemoryStorage();
  const clock = { now: 1_000_000 };
  const sent: string[] = [];
  const script: Record<string, SendOutcome[]> = {};
  let owner: string | null = options.owner === undefined ? 'teacher-1' : options.owner;
  const events: QueueEvent[] = [];
  const queue = new AwardQueue({
    storage,
    now: () => clock.now,
    getOwnerId: () => owner,
    ...(options.limits ? { limits: options.limits } : {}),
    transport: async (item) => {
      sent.push(item.opId);
      const outcomes = script[item.opId];
      return outcomes && outcomes.length > 0 ? outcomes.shift()! : OK;
    },
  });
  queue.onEvent((event) => events.push(event));
  return {
    queue,
    storage,
    clock,
    sent,
    events,
    script,
    setOwner: (next: string | null) => {
      owner = next;
    },
  };
}

describe('enqueue', () => {
  it('stores ids, category, points, op id and timestamps only', async () => {
    const t = setup();
    expect(await t.queue.enqueue(award('op1', { points: 3 }))).toBe('queued');
    const [row] = t.storage.rows;
    expect(Object.keys(row!).sort()).toEqual(
      ['attempts', 'categoryId', 'classId', 'createdAt', 'nextAttemptAt', 'opId', 'ownerId', 'points', 'schoolId', 'studentIds', 'wholeClass'].sort(),
    );
    expect(row).toMatchObject({ opId: 'op1', points: 3, createdAt: 1_000_000, attempts: 0 });
  });

  it('ignores a second enqueue of the same op id (never two awards for one action)', async () => {
    const t = setup();
    expect(await t.queue.enqueue(award('op1'))).toBe('queued');
    expect(await t.queue.enqueue(award('op1', { studentIds: ['s2'] }))).toBe('duplicate');
    expect(t.queue.count()).toBe(1);
    expect(t.storage.rows[0]!.studentIds).toEqual(['s1']);
  });

  it('refuses invalid awards', async () => {
    const t = setup();
    expect(await t.queue.enqueue(award('a', { studentIds: [] }))).toBe('invalid');
    expect(await t.queue.enqueue(award('b', { wholeClass: true, studentIds: ['s1'] }))).toBe('invalid');
    expect(await t.queue.enqueue(award('c', { points: 11 }))).toBe('invalid');
    expect(await t.queue.enqueue(award('d', { points: 0 }))).toBe('invalid');
    expect(await t.queue.enqueue(award('e', { studentIds: ['s1', 's1'] }))).toBe('invalid');
    expect(await t.queue.enqueue(award('f', { wholeClass: true, studentIds: [] }))).toBe('queued');
    expect(t.queue.count()).toBe(1);
  });

  it('caps the queue size and says so instead of dropping silently', async () => {
    const t = setup({ limits: { maxItems: 2, maxAgeMs: DEFAULT_LIMITS.maxAgeMs } });
    expect(await t.queue.enqueue(award('1'))).toBe('queued');
    expect(await t.queue.enqueue(award('2'))).toBe('queued');
    expect(await t.queue.enqueue(award('3'))).toBe('full');
    expect(t.queue.pending().map((item) => item.opId)).toEqual(['1', '2']);
  });

  it('loads what an earlier run left behind', async () => {
    const t = setup();
    await t.storage.insert({ ...award('old'), studentIds: ['s9'], createdAt: 5, attempts: 2, nextAttemptAt: 0 });
    await t.queue.init();
    expect(t.queue.pending().map((item) => item.opId)).toEqual(['old']);
  });
});

describe('flush', () => {
  it('sends in the order awards were queued and removes confirmed ones', async () => {
    const t = setup();
    await t.queue.enqueue(award('a'));
    await t.queue.enqueue(award('b'));
    await t.queue.enqueue(award('c'));
    const summary = await t.queue.flush();
    expect(t.sent).toEqual(['a', 'b', 'c']);
    expect(summary).toEqual({ confirmed: 3, rejected: 0, expired: 0, remaining: 0 });
    expect(t.storage.rows).toEqual([]);
    expect(t.events.map((event) => event.type)).toEqual(['confirmed', 'confirmed', 'confirmed']);
  });

  it('treats 201 and a 200 replay alike: done, never sent again', async () => {
    const t = setup();
    t.script.a = [REPLAY];
    await t.queue.enqueue(award('a'));
    await t.queue.flush();
    expect(t.events[0]).toMatchObject({ type: 'confirmed', replayed: true });
    await t.queue.flush(true);
    expect(t.sent).toEqual(['a']);
  });

  it('keeps an award when offline, retries it with the SAME op id, and awards once', async () => {
    const t = setup();
    t.script.a = [OFFLINE, OFFLINE, OK];
    await t.queue.enqueue(award('a'));

    let summary = await t.queue.flush();
    expect(summary.remaining).toBe(1);
    expect(t.queue.pending()[0]).toMatchObject({ opId: 'a', attempts: 1 });
    expect(t.queue.pending()[0]!.nextAttemptAt).toBe(1_000_000 + backoffMs(1));

    summary = await t.queue.flush(true); // reconnected: forced
    expect(summary.remaining).toBe(1);
    summary = await t.queue.flush(true);
    expect(summary).toMatchObject({ confirmed: 1, remaining: 0 });
    expect(t.sent).toEqual(['a', 'a', 'a']); // one op id throughout
    expect(t.events.filter((event) => event.type === 'confirmed')).toHaveLength(1);
  });

  it('respects the backoff for periodic retries and ignores it when forced', async () => {
    const t = setup();
    t.script.a = [SERVER_ERROR, OK];
    await t.queue.enqueue(award('a'));
    await t.queue.flush();
    expect(t.sent).toEqual(['a']);

    await t.queue.flush(); // too early
    expect(t.sent).toEqual(['a']);

    t.clock.now += backoffMs(1) + 1;
    await t.queue.flush();
    expect(t.sent).toEqual(['a', 'a']);
    expect(t.queue.count()).toBe(0);
  });

  it('backs off exponentially, capped at five minutes', () => {
    expect(backoffMs(1)).toBe(2_000);
    expect(backoffMs(2)).toBe(4_000);
    expect(backoffMs(3)).toBe(8_000);
    expect(backoffMs(50)).toBe(5 * 60_000);
  });

  it('honours a Retry-After longer than the backoff', async () => {
    const t = setup();
    t.script.a = [{ kind: 'retry', reason: 'throttled', retryAfterMs: 60_000 }];
    await t.queue.enqueue(award('a'));
    await t.queue.flush();
    expect(t.queue.pending()[0]!.nextAttemptAt).toBe(1_000_000 + 60_000);
  });

  it('stops at the first network failure so order is kept and nothing else is tried', async () => {
    const t = setup();
    t.script.a = [OFFLINE];
    await t.queue.enqueue(award('a'));
    await t.queue.enqueue(award('b'));
    await t.queue.flush();
    expect(t.sent).toEqual(['a']);
    expect(t.queue.pending().map((item) => item.opId)).toEqual(['a', 'b']);
  });

  it('goes on with the next award after a server error, keeping the failed one', async () => {
    const t = setup();
    t.script.a = [SERVER_ERROR];
    await t.queue.enqueue(award('a'));
    await t.queue.enqueue(award('b'));
    const summary = await t.queue.flush();
    expect(t.sent).toEqual(['a', 'b']);
    expect(summary).toMatchObject({ confirmed: 1, remaining: 1 });
    expect(t.queue.pending().map((item) => item.opId)).toEqual(['a']);
  });

  it('treats a transport that throws as offline', async () => {
    const storage = new MemoryStorage();
    const queue = new AwardQueue({
      storage,
      now: () => 1,
      getOwnerId: () => 'teacher-1',
      transport: async () => {
        throw new Error('boom');
      },
    });
    await queue.enqueue(award('a'));
    await queue.flush();
    expect(queue.count()).toBe(1);
  });

  it('drops a refused award for good (4xx) and says which one, without blocking the rest (partial failure)', async () => {
    const t = setup();
    t.script.b = [{ kind: 'rejected', status: 403, code: 'class.access_denied', detail: 'Not your class' }];
    await t.queue.enqueue(award('a'));
    await t.queue.enqueue(award('b', { studentIds: ['s2'], categoryId: 'cat-2' }));
    await t.queue.enqueue(award('c'));
    const summary = await t.queue.flush();
    expect(summary).toEqual({ confirmed: 2, rejected: 1, expired: 0, remaining: 0 });
    const rejected = t.events.find((event) => event.type === 'rejected');
    expect(rejected).toMatchObject({ status: 403, code: 'class.access_denied', detail: 'Not your class' });
    expect(rejected && 'item' in rejected ? rejected.item : null).toMatchObject({ studentIds: ['s2'], categoryId: 'cat-2' });
    expect(t.sent).toEqual(['a', 'b', 'c']);
    t.sent.length = 0;
    await t.queue.flush(true);
    expect(t.sent).toEqual([]); // the rejected one is not retried
  });

  it('joins a flush that is running and looks again for awards queued meanwhile', async () => {
    const storage = new MemoryStorage();
    const sent: string[] = [];
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const queue = new AwardQueue({
      storage,
      now: () => 1,
      getOwnerId: () => 'teacher-1',
      transport: async (item) => {
        sent.push(item.opId);
        if (item.opId === 'a') await gate;
        return OK;
      },
    });
    await queue.enqueue(award('a'));
    const first = queue.flush();
    await Promise.resolve();
    await queue.enqueue(award('b'));
    const second = queue.flush();
    release();
    await Promise.all([first, second]);
    expect(sent).toEqual(['a', 'b']); // each sent once
    expect(queue.count()).toBe(0);
  });

  it('never sends one op twice at the same time', async () => {
    const storage = new MemoryStorage();
    let concurrent = 0;
    let maxConcurrent = 0;
    const queue = new AwardQueue({
      storage,
      now: () => 1,
      getOwnerId: () => 'teacher-1',
      transport: async () => {
        concurrent++;
        maxConcurrent = Math.max(maxConcurrent, concurrent);
        await new Promise((resolve) => setTimeout(resolve, 5));
        concurrent--;
        return OK;
      },
    });
    await queue.enqueue(award('a'));
    await Promise.all([queue.flush(), queue.flush(true), queue.flush()]);
    expect(maxConcurrent).toBe(1);
  });
});

describe('expiry', () => {
  it('gives up on awards older than the cap and reports them', async () => {
    const t = setup({ limits: { maxItems: 10, maxAgeMs: 60_000 } });
    await t.queue.enqueue(award('old'));
    t.clock.now += 30_000;
    await t.queue.enqueue(award('fresh', { studentIds: ['s2'] }));
    t.clock.now += 40_000; // old is now 70 s, fresh 40 s
    const summary = await t.queue.flush(true);
    expect(summary).toMatchObject({ confirmed: 1, expired: 1, remaining: 0 });
    expect(t.sent).toEqual(['fresh']);
    expect(t.events.find((event) => event.type === 'expired')).toMatchObject({ item: { opId: 'old' } });
  });

  it('expires on the next flush even when the award was still failing', async () => {
    const t = setup({ limits: { maxItems: 10, maxAgeMs: 60_000 } });
    t.script.a = [OFFLINE];
    await t.queue.enqueue(award('a'));
    await t.queue.flush();
    t.clock.now += 61_000;
    await t.queue.flush(true);
    expect(t.queue.count()).toBe(0);
    expect(t.sent).toEqual(['a']);
  });
});

describe('cancel (undo before it was sent)', () => {
  it('removes a waiting award so it is never sent', async () => {
    const t = setup();
    await t.queue.enqueue(award('a'));
    expect(await t.queue.cancel('a')).toBe('cancelled');
    await t.queue.flush();
    expect(t.sent).toEqual([]);
    expect(t.storage.rows).toEqual([]);
  });

  it('reports an unknown op, and one that is in the air right now', async () => {
    const storage = new MemoryStorage();
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const queue = new AwardQueue({
      storage,
      now: () => 1,
      getOwnerId: () => 'teacher-1',
      transport: async () => {
        await gate;
        return OK;
      },
    });
    await queue.enqueue(award('a'));
    expect(await queue.cancel('nope')).toBe('not-found');
    const flushing = queue.flush();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(await queue.cancel('a')).toBe('in-flight');
    release();
    await flushing;
    expect(await queue.cancel('a')).toBe('not-found'); // confirmed meanwhile
  });
});

describe('owner and sign-out', () => {
  it('sends nothing while nobody is signed in', async () => {
    const t = setup({ owner: null });
    await t.queue.enqueue(award('a'));
    await t.queue.flush(true);
    expect(t.sent).toEqual([]);
    expect(t.queue.count()).toBe(1);
  });

  it('never sends one teacher\'s awards under another teacher\'s session', async () => {
    const t = setup();
    await t.queue.enqueue(award('a'));
    t.setOwner('teacher-2');
    await t.queue.enqueue(award('b', { ownerId: 'teacher-2' }));
    await t.queue.flush(true);
    expect(t.sent).toEqual(['b']);
    expect(t.events.find((event) => event.type === 'discarded')).toMatchObject({ item: { opId: 'a' } });
    expect(t.storage.rows).toEqual([]);
  });

  it('clear() wipes memory and storage', async () => {
    const t = setup();
    await t.queue.enqueue(award('a'));
    await t.queue.enqueue(award('b'));
    await t.queue.clear();
    expect(t.queue.count()).toBe(0);
    expect(t.storage.rows).toEqual([]);
    await t.queue.flush(true);
    expect(t.sent).toEqual([]);
  });

  it('a send that was in the air at sign-out does not touch the new state or raise events', async () => {
    const storage = new MemoryStorage();
    const events: QueueEvent[] = [];
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const queue = new AwardQueue({
      storage,
      now: () => 1,
      getOwnerId: () => 'teacher-1',
      transport: async () => {
        await gate;
        return OK;
      },
    });
    queue.onEvent((event) => events.push(event));
    await queue.enqueue(award('a'));
    await queue.enqueue(award('b'));
    const flushing = queue.flush();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await queue.clear();
    release();
    await flushing;
    expect(events).toEqual([]);
    expect(queue.count()).toBe(0);
    expect(storage.rows).toEqual([]);
  });

  it('notifies subscribers when the items change', async () => {
    const t = setup();
    let calls = 0;
    t.queue.subscribe(() => calls++);
    await t.queue.enqueue(award('a'));
    await t.queue.flush();
    expect(calls).toBeGreaterThanOrEqual(2);
  });
});
