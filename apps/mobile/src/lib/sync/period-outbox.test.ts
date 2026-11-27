import { describe, expect, it } from 'vitest';

import { resolveConflict } from '@/lib/attendance/conflicts';

import { SyncEngine } from './engine';
import { canMerge, isValidOperation, mergeKeyOf } from './operations';
import { toRequestOperation } from './protocol';
import {
  APPLIED,
  MemoryStorage,
  all,
  conflictState,
  mark,
  owner,
  results,
  roll,
  u,
  type Script,
} from './test-support';
import { DEFAULT_LIMITS, type QueuedAttendance, type QueuedOperation } from './types';

const SUBJECT = u(700);
const OTHER_SUBJECT = u(701);
const GROUP = u(800);
const OTHER_GROUP = u(801);

function setup() {
  const storage = new MemoryStorage();
  const clock = { now: 1_000_000 };
  const batches: QueuedOperation[][] = [];
  let script: Script = (batch) => all(batch, APPLIED);
  const engine = new SyncEngine({
    storage,
    now: () => clock.now,
    getOwnerId: () => owner,
    limits: DEFAULT_LIMITS,
    transport: async (batch) => {
      batches.push([...batch]);
      return script(batch, batches.length);
    },
  });
  return {
    engine,
    storage,
    batches,
    script: (next: Script) => {
      script = next;
    },
  };
}

describe('period rolls in the outbox', () => {
  it('two slots of the same day are two different rolls: they never merge and both are sent', async () => {
    const t = setup();
    expect(await t.engine.enqueue(roll(1, [mark(1, 'ABSENT')], { slot: 'P1', subjectId: SUBJECT }))).toBe('queued');
    expect(await t.engine.enqueue(roll(2, [mark(1, 'PRESENT')], { slot: 'P2', subjectId: OTHER_SUBJECT }))).toBe('queued');
    expect(t.engine.count()).toBe(2);
    expect(mergeKeyOf(roll(1, [mark(1)], { slot: 'P1' }))).not.toBe(mergeKeyOf(roll(2, [mark(1)], { slot: 'P2' })));

    await t.engine.flush(true);
    expect(t.batches).toHaveLength(1);
    expect(t.batches[0]!.map((op) => (op as QueuedAttendance).payload.slot)).toEqual(['P1', 'P2']);
    expect(t.engine.count()).toBe(0);
  });

  it('edits of the same period, subject and group still become one operation', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'ABSENT')], { slot: 'P3', subjectId: SUBJECT, groupId: GROUP }));
    expect(await t.engine.enqueue(roll(2, [mark(2, 'LATE', { minutesLate: 5 })], { slot: 'P3', subjectId: SUBJECT, groupId: GROUP }))).toBe('merged');
    expect(t.engine.count()).toBe(1);
    const [only] = t.engine.pending() as QueuedAttendance[];
    expect(only!.payload).toMatchObject({ slot: 'P3', subjectId: SUBJECT, groupId: GROUP });
    expect(only!.payload.records.map((record) => record.studentId)).toEqual([u(101), u(102)]);
  });

  it('an edit for another group of the same roll is its own operation, so the order of edits is kept', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'ABSENT')], { slot: 'P3', subjectId: SUBJECT, groupId: GROUP }));
    await t.engine.enqueue(roll(2, [mark(1, 'PRESENT')], { slot: 'P3', subjectId: SUBJECT, groupId: OTHER_GROUP }));
    // The same student is in both groups. Merging this edit into the FIRST operation would let it jump over the
    // second one and the server would end with PRESENT: it must queue behind it, as the teacher made it.
    expect(await t.engine.enqueue(roll(3, [mark(1, 'LATE', { minutesLate: 5 })], { slot: 'P3', subjectId: SUBJECT, groupId: GROUP }))).toBe('queued');
    const queued = t.engine.pending() as QueuedAttendance[];
    expect(queued.map((op) => [op.payload.groupId, op.payload.records[0]!.status])).toEqual([
      [GROUP, 'ABSENT'],
      [OTHER_GROUP, 'PRESENT'],
      [GROUP, 'LATE'],
    ]);
  });

  it('keeps the subject and group on the wire and in what a restart reloads', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'ABSENT')], { slot: 'P4', subjectId: SUBJECT, groupId: GROUP }));
    const [row] = t.storage.rows as QueuedAttendance[];
    expect(row!.payload).toMatchObject({ slot: 'P4', subjectId: SUBJECT, groupId: GROUP });

    const [item] = t.engine.pending();
    const wire = toRequestOperation(item!, 'device-1');
    expect(wire.payload).toMatchObject({ slot: 'P4', subjectId: SUBJECT, groupId: GROUP, classId: u(901) });
  });

  it('sends no subject or group for a roll that has none', () => {
    const wire = toRequestOperation({ ...roll(1, [mark(1)]), createdAt: 1, attempts: 0, nextAttemptAt: 0, status: 'PENDING' } as QueuedOperation, 'device-1');
    expect(Object.keys(wire.payload)).not.toContain('subjectId');
    expect(Object.keys(wire.payload)).not.toContain('groupId');
  });

  it('refuses a subject on a slot that is not a period, and ids that are not ids', () => {
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'DAY', subjectId: SUBJECT }))).toBe(false);
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'AM', subjectId: SUBJECT }))).toBe(false);
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'P9', subjectId: SUBJECT }))).toBe(false);
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'P1', subjectId: 'maths' }))).toBe(false);
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'DAY', groupId: 'x' }))).toBe(false);
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'P8', subjectId: SUBJECT, groupId: GROUP }))).toBe(true);
    // A group alone is fine on any slot (an elective on the whole day).
    expect(isValidOperation(roll(1, [mark(1)], { slot: 'DAY', groupId: GROUP }))).toBe(true);
  });

  it('canMerge compares subject and group, treating absent as none', () => {
    const base = roll(1, [mark(1)], { slot: 'P1' }) as QueuedAttendance;
    expect(canMerge(base, roll(2, [mark(2)], { slot: 'P1' }) as QueuedAttendance)).toBe(true);
    expect(canMerge(base, roll(2, [mark(2)], { slot: 'P1', subjectId: SUBJECT }) as QueuedAttendance)).toBe(false);
    expect(canMerge(base, roll(2, [mark(2)], { slot: 'P1', groupId: GROUP }) as QueuedAttendance)).toBe(false);
  });
});

describe('conflicts per slot', () => {
  it('a conflict on one period leaves the other period untouched and names its own slot', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'PRESENT')], { slot: 'P1', subjectId: SUBJECT }));
    await t.engine.enqueue(roll(2, [mark(1, 'PRESENT')], { slot: 'P2', subjectId: SUBJECT }));
    t.script((batch) =>
      results({
        [batch[0]!.opId]: { kind: 'conflict', version: 7, state: conflictState(1, 'ABSENT') },
        [batch[1]!.opId]: APPLIED,
      }),
    );
    await t.engine.flush(true);

    const waiting = t.engine.attention() as QueuedAttendance[];
    expect(waiting).toHaveLength(1);
    expect(waiting[0]!.payload.slot).toBe('P1');
    expect(waiting[0]!.conflict?.entries[0]).toMatchObject({ studentId: u(101), stored: { status: 'ABSENT' }, submitted: { status: 'PRESENT' } });
    expect(t.engine.pending()).toHaveLength(0);
  });

  it('"use mine" resends to the same period, subject and group with the stored version', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1, 'PRESENT')], { slot: 'P5', subjectId: SUBJECT, groupId: GROUP }));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'conflict', version: 7, state: conflictState(1, 'ABSENT') } }));
    await t.engine.flush(true);

    const [settled] = t.engine.attention() as QueuedAttendance[];
    const resolution = resolveConflict(settled!, [u(101)], 'use-mine', u(50));
    expect(resolution.resend).toMatchObject({
      opId: u(50),
      baseVersion: 7,
      payload: { slot: 'P5', subjectId: SUBJECT, groupId: GROUP, records: [{ studentId: u(101), status: 'PRESENT', expectedVersion: 2 }] },
    });
    expect(isValidOperation(resolution.resend!)).toBe(true);
  });

  it('a rejection (for example a subject that does not match the roll) is kept with the API detail', async () => {
    const t = setup();
    await t.engine.enqueue(roll(1, [mark(1)], { slot: 'P1', subjectId: SUBJECT }));
    t.script((batch) => results({ [batch[0]!.opId]: { kind: 'rejected', problem: { status: 409, code: 'attendance.subject_mismatch', detail: 'detail' } } }));
    await t.engine.flush(true);
    const [row] = t.engine.attention() as QueuedAttendance[];
    expect(row).toMatchObject({ status: 'REJECTED', problem: { code: 'attendance.subject_mismatch' }, payload: { slot: 'P1', subjectId: SUBJECT } });
  });
});
