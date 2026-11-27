import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';
import { groupsFromCache, groupsToCache, rollEntryKey, rollFromCache, rosterToCache, subjectsFromCache } from '@/lib/offline/cache';
import type { CacheLike } from '@/lib/offline/cached-fetch';
import { CLASS, SCHOOL, mark, roll as rollOperation, u } from '@/lib/sync/test-support';
import type { QueuedOperation } from '@/lib/sync/types';

import { activeClassSubjects, groupMembers, restrictToGroup, subjectChoice, subjectNameOf } from './period';
import { loadRoll, rollFromOfflineCache, type RollDeps } from './roll-load';
import { editOperation, editability, rollTrust, type RollSnapshot, type RollStudent } from './roll-model';

const ZONE = 'Africa/Addis_Ababa';
// 2026-11-25 10:00 in Addis Ababa.
const NOW = Date.UTC(2026, 10, 25, 7, 0);
const DAY_MS = 86_400_000;
const SUBJECT = u(700);
const GROUP = u(800);

const student = (n: number): RollStudent => ({ studentId: u(100 + n), name: `Student ${n}`, avatarMediaId: null, avatarColor: null, record: null, suggestedStatus: null, absence: null });

const period = (slot: string, overrides: Partial<RollSnapshot> = {}): RollSnapshot => ({
  schoolId: SCHOOL,
  classId: CLASS,
  date: '2026-11-25',
  slot,
  slots: ['P1', 'P2', 'P3'],
  timeZone: ZONE,
  sessionVersion: null,
  locked: false,
  locksAt: '2026-11-27T21:00:00Z',
  mayEditLocked: false,
  synthetic: false,
  subjectId: null,
  subjectName: null,
  groupId: null,
  students: [student(1), student(2), student(3)],
  ...overrides,
});

const waiting = (n: number, overrides: Parameters<typeof rollOperation>[2] = {}): QueuedOperation =>
  ({ ...rollOperation(n, [mark(1)], { slot: 'P1', ...overrides }), createdAt: n, attempts: 0, nextAttemptAt: 0, status: 'PENDING' }) as QueuedOperation;

describe('subjectChoice', () => {
  it('has no subject outside the periods', () => {
    expect(subjectChoice(period('DAY'), [], SUBJECT)).toEqual({ subjectId: null, locked: true, applicable: false });
    expect(subjectChoice(period('AM'), [], SUBJECT).applicable).toBe(false);
  });

  it('lets the teacher choose while the roll is new, and carries the draft', () => {
    expect(subjectChoice(period('P1'), [], null)).toEqual({ subjectId: null, locked: false, applicable: true });
    expect(subjectChoice(period('P1'), [], SUBJECT)).toEqual({ subjectId: SUBJECT, locked: false, applicable: true });
  });

  it('is the roll\'s own subject once it has one (the server refuses a different one)', () => {
    expect(subjectChoice(period('P1', { subjectId: OTHER, sessionVersion: 2 }), [], SUBJECT)).toEqual({ subjectId: OTHER, locked: true, applicable: true });
  });

  it('a stored roll without a subject can not be given one later', () => {
    expect(subjectChoice(period('P1', { sessionVersion: 0 }), [], SUBJECT)).toEqual({ subjectId: null, locked: true, applicable: true });
  });

  it('after the first waiting edit the subject is what that edit named, whatever the draft says now', () => {
    const named = waiting(1, { subjectId: SUBJECT });
    expect(subjectChoice(period('P1'), [named], OTHER)).toEqual({ subjectId: SUBJECT, locked: true, applicable: true });
    // A first edit that named none keeps the roll without one.
    expect(subjectChoice(period('P1'), [waiting(2)], OTHER)).toEqual({ subjectId: null, locked: true, applicable: true });
  });

  it('only the waiting edits of this very roll count (another slot is another roll)', () => {
    expect(subjectChoice(period('P2'), [waiting(1, { slot: 'P1', subjectId: SUBJECT })], null)).toEqual({ subjectId: null, locked: false, applicable: true });
    expect(subjectChoice(period('P1'), [waiting(1, { slot: 'P1', date: '2026-11-24', subjectId: SUBJECT })], null).locked).toBe(false);
  });
});

const OTHER = u(701);

describe('editOperation for a period roll', () => {
  const marks = [mark(1, 'ABSENT')];
  it('carries slot, subject and group of the roll the teacher is on', () => {
    const operation = editOperation({ opId: u(1), ownerId: 'teacher-1', roll: period('P2'), marks, subjectId: SUBJECT, groupId: GROUP });
    expect(operation).toMatchObject({ payload: { slot: 'P2', subjectId: SUBJECT, groupId: GROUP } });
  });

  it('never names a subject on a whole-day roll', () => {
    const operation = editOperation({ opId: u(1), ownerId: 'teacher-1', roll: period('DAY'), marks, subjectId: SUBJECT });
    expect((operation.payload as { subjectId?: string }).subjectId).toBeUndefined();
  });

  it('adds nothing when there is no subject or group', () => {
    const operation = editOperation({ opId: u(1), ownerId: 'teacher-1', roll: period('P1'), marks });
    expect(Object.keys(operation.payload)).toEqual(['date', 'slot', 'records']);
  });
});

describe('class subjects and groups', () => {
  it('offers the class\'s own subjects that are still active, by name', () => {
    const catalogue = [
      { id: u(1), name: 'Mathematics', active: true },
      { id: u(2), name: 'Art', active: true },
      { id: u(3), name: 'Retired', active: false },
      { id: u(4), name: 'Not in this class', active: true },
    ];
    expect(activeClassSubjects([u(1), u(2), u(3)], catalogue)).toEqual([
      { id: u(2), name: 'Art' },
      { id: u(1), name: 'Mathematics' },
    ]);
  });

  it('names the subject of a roll from the roll, else from the class list', () => {
    const subjects = [{ id: SUBJECT, name: 'Science' }];
    expect(subjectNameOf(null, period('P1'), subjects)).toBeNull();
    expect(subjectNameOf(SUBJECT, period('P1'), subjects)).toBe('Science');
    expect(subjectNameOf(SUBJECT, period('P1', { subjectId: SUBJECT, subjectName: 'Natural science' }), subjects)).toBe('Natural science');
  });

  it('cuts a roll and a roster to the members of a group', () => {
    const group = { id: GROUP, studentIds: [u(102), u(103), u(999)] };
    expect(restrictToGroup(period('P1'), group).students.map((s) => s.studentId)).toEqual([u(102), u(103)]);
    expect(restrictToGroup(period('P1'), group).groupId).toBe(GROUP);
    expect(groupMembers([{ id: u(101) }, { id: u(102) }], group)).toEqual([{ id: u(102) }]);
  });

  it('stores groups and subjects as ids and names only', () => {
    const stored = groupsToCache([{ id: GROUP, name: 'Elective A', studentIds: [u(101)], extra: 'x' } as never]);
    expect(stored).toEqual([{ id: GROUP, name: 'Elective A', studentIds: [u(101)] }]);
    expect(groupsFromCache(JSON.parse(JSON.stringify(stored)))).toEqual(stored);
    expect(groupsFromCache('x')).toBeNull();
    expect(subjectsFromCache([{ id: SUBJECT, name: 'Science', color: '#fff' }, { id: 'bad', name: 'x' }])).toEqual([{ id: SUBJECT, name: 'Science' }]);
  });
});

// --- the offline cache and the load path -------------------------------------------------------------------------

function memory() {
  const rows = new Map<string, { data: unknown; fetchedAt: number }>();
  const key = (kind: string, scope: string, entry = '') => `${kind}|${scope}|${entry}`;
  const cache: CacheLike = {
    async get(kind, scope, entry) {
      return rows.get(key(kind, scope, entry)) ?? null;
    },
    async put(kind, scope, entry, data, fetchedAt = 0) {
      rows.set(key(kind, scope, entry), { data: JSON.parse(JSON.stringify(data)), fetchedAt });
    },
    async forgetClass() {},
  };
  return { cache, rows, key };
}

const offline = async () => {
  throw new ApiRequestError(undefined);
};
const deps = (m: ReturnType<typeof memory>, fetchRoll: RollDeps['fetchRoll'], now = NOW): RollDeps => ({ cache: m.cache, fetchRoll, now: () => now });
const request = { schoolId: SCHOOL, classId: CLASS, date: null, slot: null };

describe('period rolls in the offline cache', () => {
  it('keeps one entry per date and slot: switching slot never overwrites another slot\'s roll', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => period('P1', { sessionVersion: 3, subjectId: SUBJECT, subjectName: 'Science' })), { ...request, slot: 'P1' });
    await loadRoll(deps(m, async () => period('P2', { sessionVersion: 8 })), { ...request, slot: 'P2' });

    const p1 = rollFromCache(m.rows.get(m.key('roll', CLASS, rollEntryKey('2026-11-25', 'P1')))?.data);
    const p2 = rollFromCache(m.rows.get(m.key('roll', CLASS, rollEntryKey('2026-11-25', 'P2')))?.data);
    expect(p1).toMatchObject({ slot: 'P1', sessionVersion: 3, subjectId: SUBJECT, subjectName: 'Science' });
    expect(p2).toMatchObject({ slot: 'P2', sessionVersion: 8, subjectId: null });
    expect(rollEntryKey('2026-11-25', 'P1')).not.toBe(rollEntryKey('2026-11-25', 'P2'));
  });

  it('offline, each slot comes from its own entry and keeps its subject', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => period('P1', { sessionVersion: 3, subjectId: SUBJECT, subjectName: 'Science' })), { ...request, slot: 'P1' });
    await loadRoll(deps(m, async () => period('P2', { sessionVersion: 8 })), { ...request, slot: 'P2' });
    const p1 = await loadRoll(deps(m, offline), { ...request, slot: 'P1' });
    const p2 = await loadRoll(deps(m, offline), { ...request, slot: 'P2' });
    expect(p1.data).toMatchObject({ slot: 'P1', sessionVersion: 3, subjectId: SUBJECT });
    expect(p2.data).toMatchObject({ slot: 'P2', sessionVersion: 8, subjectId: null });
  });

  it('a slot nobody loaded is built from the class list for today only, with the slots the school enabled', async () => {
    const m = memory();
    await m.cache.put('rollmeta', CLASS, '', { timeZone: ZONE, slots: ['P1', 'P2', 'P3'] }, NOW);
    await m.cache.put('roster', CLASS, '', rosterToCache([{ id: u(101), name: 'Abel', firstName: 'Abel', avatarMediaId: null, avatarConfig: null, accessLevel: 'BASIC' }]), NOW);
    const built = await loadRoll(deps(m, offline), { ...request, slot: 'P3' });
    expect(built.data).toMatchObject({ slot: 'P3', synthetic: true, subjectId: null, groupId: null, date: '2026-11-25' });
    // A slot the school does not use is not invented.
    await expect(loadRoll(deps(m, offline), { ...request, slot: 'P7' })).rejects.toBeInstanceOf(ApiRequestError);
  });

  it('asks the server for a group\'s roll with the group id, and never stores it as a roll of its own', async () => {
    const m = memory();
    const asked: (string | null)[] = [];
    const group = await loadRoll(
      deps(m, async (_date, _slot, groupId) => {
        asked.push(groupId);
        return period('P1', { groupId, students: [student(2)] });
      }),
      { ...request, slot: 'P1', groupId: GROUP },
    );
    expect(asked).toEqual([GROUP]);
    expect(group.data.students).toHaveLength(1);
    await Promise.resolve();
    expect(m.rows.has(m.key('roll', CLASS, rollEntryKey('2026-11-25', 'P1')))).toBe(false);
    // The zone and the slots still are worth keeping.
    expect(m.rows.has(m.key('rollmeta', CLASS))).toBe(true);
  });

  it('offline, a group\'s roll is the cached class roll cut to its members', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => period('P1', { sessionVersion: 3 })), { ...request, slot: 'P1' });
    await m.cache.put('groups', CLASS, '', groupsToCache([{ id: GROUP, name: 'Elective', studentIds: [u(101), u(103)] }]), NOW);
    const result = await loadRoll(deps(m, offline), { ...request, slot: 'P1', groupId: GROUP });
    expect(result).toMatchObject({ fromCache: true, data: { groupId: GROUP, sessionVersion: 3 } });
    expect(result.data.students.map((s) => s.studentId)).toEqual([u(101), u(103)]);
  });

  it('offline, a group that was never cached shows nothing rather than the wrong students', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => period('P1')), { ...request, slot: 'P1' });
    expect(await rollFromOfflineCache({ cache: m.cache, now: () => NOW }, { ...request, slot: 'P1', groupId: GROUP })).toBeNull();
  });

  it('offline with no roll yet, a group\'s roll is built from the members of the class list', async () => {
    const m = memory();
    await m.cache.put('rollmeta', CLASS, '', { timeZone: ZONE, slots: ['P1'] }, NOW);
    await m.cache.put(
      'roster',
      CLASS,
      '',
      rosterToCache([
        { id: u(101), name: 'Abel', firstName: 'Abel', avatarMediaId: null, avatarConfig: null, accessLevel: 'BASIC' },
        { id: u(102), name: 'Bina', firstName: 'Bina', avatarMediaId: null, avatarConfig: null, accessLevel: 'BASIC' },
      ]),
      NOW,
    );
    await m.cache.put('groups', CLASS, '', groupsToCache([{ id: GROUP, name: 'Elective', studentIds: [u(102)] }]), NOW);
    const result = await loadRoll(deps(m, offline), { ...request, groupId: GROUP });
    expect(result.data).toMatchObject({ synthetic: true, groupId: GROUP });
    expect(result.data.students.map((s) => s.studentId)).toEqual([u(102)]);
  });
});

describe('the 3-day edit limit applies to a period roll', () => {
  it('a cached P2 roll older than three days is shown but not edited; a fresh one is open', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => period('P2', { date: '2026-11-25', sessionVersion: 1 })), { ...request, date: '2026-11-25', slot: 'P2' });
    const read = (now: number) => rollFromOfflineCache({ cache: m.cache, now: () => now }, { ...request, date: '2026-11-25', slot: 'P2' });

    const fresh = (await read(NOW + 2 * DAY_MS))!;
    const trustFresh = rollTrust(fresh.fetchedAt, NOW + 2 * DAY_MS, fresh.data.synthetic, fresh.fromCache);
    expect(trustFresh).toBe('cached');
    expect(editability(fresh.data, trustFresh, NOW + 2 * DAY_MS).kind).toBe('open');

    const old = (await read(NOW + 4 * DAY_MS))!;
    const trustOld = rollTrust(old.fetchedAt, NOW + 4 * DAY_MS, old.data.synthetic, old.fromCache);
    expect(trustOld).toBe('stale');
    expect(editability(old.data, trustOld, NOW + 4 * DAY_MS).kind).toBe('stale');
  });
});
