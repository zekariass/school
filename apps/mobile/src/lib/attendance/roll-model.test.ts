import { describe, expect, it } from 'vitest';

import { CLASS, SCHOOL, conflictState, mark, roll as rollOperation, u } from '@/lib/sync/test-support';
import type { QueuedOperation } from '@/lib/sync/types';

import {
  ageOf,
  applyServerState,
  countStatuses,
  editability,
  editOperation,
  effectiveStudents,
  excusedByHint,
  isRollLocked,
  markAllPresentMarks,
  markFor,
  nextStatus,
  rollFromApi,
  rollTrust,
  syntheticRoll,
  type RollSnapshot,
  type RollStudent,
} from './roll-model';

const student = (n: number, overrides: Partial<RollStudent> = {}): RollStudent => ({
  studentId: u(100 + n),
  name: `Student ${n}`,
  avatarMediaId: null,
  avatarColor: null,
  record: null,
  suggestedStatus: null,
  absence: null,
  ...overrides,
});

const roll = (students: RollStudent[], overrides: Partial<RollSnapshot> = {}): RollSnapshot => ({
  schoolId: SCHOOL,
  classId: CLASS,
  date: '2026-11-25',
  slot: 'DAY',
  slots: ['DAY'],
  timeZone: 'Africa/Addis_Ababa',
  sessionVersion: 3,
  locked: false,
  locksAt: '2026-11-27T21:00:00Z',
  mayEditLocked: false,
  synthetic: false,
  students,
  ...overrides,
});

const pending = (n: number, records = [mark(1, 'ABSENT')], extra: Parameters<typeof rollOperation>[2] = {}): QueuedOperation =>
  ({ ...rollOperation(n, records, extra), createdAt: n, attempts: 0, nextAttemptAt: 0, status: 'PENDING' }) as QueuedOperation;

describe('rollFromApi', () => {
  it('keeps what the app needs and leaves out notes, signed links and reasons in words', () => {
    const snapshot = rollFromApi(
      {
        classId: CLASS,
        date: '2026-11-25',
        slot: 'DAY',
        slots: ['DAY', 'PM'],
        timeZone: 'Africa/Addis_Ababa',
        locksAt: '2026-11-26T21:00:00Z',
        locked: false,
        mayEditLocked: true,
        sessionVersion: 4,
        counts: {} as never,
        students: [
          {
            studentId: u(101),
            displayName: 'Abel Kebede',
            avatar: { id: u(300), variants: [] } as never,
            avatarConfig: { color: '#336699', secret: 'x' },
            record: { id: u(400), status: 'ABSENT', note: 'fever, father called', version: 2, recordedBy: u(5), recordedAt: 'x', source: 'APP' } as never,
            suggestedStatus: 'EXCUSED',
            absence: { reportId: u(500), status: 'ACKNOWLEDGED', reasonCode: 'ILLNESS', note: 'flu since monday', fromDate: '2026-11-25', toDate: '2026-11-26' },
          },
          { studentId: u(102), displayName: 'Bruk' },
        ],
      } as never,
      SCHOOL,
    );
    expect(snapshot).toMatchObject({ sessionVersion: 4, slots: ['DAY', 'PM'], mayEditLocked: true, synthetic: false });
    expect(snapshot.students[0]).toEqual({
      studentId: u(101),
      name: 'Abel Kebede',
      avatarMediaId: u(300),
      avatarColor: '#336699',
      record: { status: 'ABSENT', minutesLate: null, version: 2, recordedBy: u(5) },
      suggestedStatus: 'EXCUSED',
      absence: { reportId: u(500), status: 'ACKNOWLEDGED', reasonCode: 'ILLNESS', fromDate: '2026-11-25', toDate: '2026-11-26' },
    });
    expect(JSON.stringify(snapshot)).not.toMatch(/fever|flu since|secret|variants/);
    expect(snapshot.students[1]).toMatchObject({ record: null, suggestedStatus: null, absence: null });
  });
});

describe('effectiveStudents: the stored roll with the waiting edits on top', () => {
  const base = roll([student(1), student(2, { record: { status: 'PRESENT', minutesLate: null, version: 1, recordedBy: null } }), student(3)]);

  it('shows the stored marks when nothing waits', () => {
    expect(effectiveStudents(base, []).map((s) => [s.status, s.queued])).toEqual([
      [null, false],
      ['PRESENT', false],
      [null, false],
    ]);
  });

  it('applies pending edits in order, a later one winning, and flags the students they touch', () => {
    const edits = [pending(1, [mark(1, 'ABSENT'), mark(2, 'LATE', { minutesLate: 9 })]), pending(2, [mark(1, 'EXCUSED')])];
    const view = effectiveStudents(base, edits);
    expect(view[0]).toMatchObject({ status: 'EXCUSED', queued: true });
    expect(view[1]).toMatchObject({ status: 'LATE', minutesLate: 9, queued: true });
    expect(view[2]).toMatchObject({ status: null, queued: false });
  });

  it('ignores edits of another roll, class or date, and anything that is not pending', () => {
    const edits = [
      pending(1, [mark(1)], { date: '2026-11-24' }),
      pending(2, [mark(1)], { slot: 'PM' }),
      pending(3, [mark(1)], { classId: u(950) }),
      { ...pending(4), status: 'CONFLICT' } as QueuedOperation,
    ];
    expect(effectiveStudents(base, edits).every((s) => !s.queued)).toBe(true);
  });
});

describe('what a tap does', () => {
  it('cycles PRESENT, ABSENT, LATE, EXCUSED, LEFT_EARLY and round again', () => {
    expect(nextStatus('PRESENT')).toBe('ABSENT');
    expect(nextStatus('ABSENT')).toBe('LATE');
    expect(nextStatus('LATE')).toBe('EXCUSED');
    expect(nextStatus('EXCUSED')).toBe('LEFT_EARLY');
    expect(nextStatus('LEFT_EARLY')).toBe('PRESENT');
  });
  it('starts an unmarked student at PRESENT, or at the parent\'s acknowledged absence', () => {
    expect(nextStatus(null)).toBe('PRESENT');
    expect(nextStatus(null, 'EXCUSED')).toBe('EXCUSED');
  });
  it('a mark carries the record version the teacher saw, and minutes only for LATE', () => {
    const seen = student(1, { record: { status: 'ABSENT', minutesLate: null, version: 4, recordedBy: null } });
    expect(markFor(seen, 'LATE', 12)).toEqual({ studentId: u(101), status: 'LATE', minutesLate: 12, expectedVersion: 4 });
    expect(markFor(seen, 'PRESENT', 12)).toEqual({ studentId: u(101), status: 'PRESENT', expectedVersion: 4 });
    expect(markFor(student(2), 'ABSENT')).toEqual({ studentId: u(102), status: 'ABSENT' });
  });
  it('an edit is based on the roll version the teacher saw', () => {
    const op = editOperation({ opId: u(7), ownerId: 'me', roll: roll([student(1)], { sessionVersion: 9 }), marks: [markFor(student(1), 'ABSENT')], overrideReason: 'late entry' });
    expect(op).toMatchObject({ type: 'ATTENDANCE_MARK', baseVersion: 9, classId: CLASS, payload: { date: '2026-11-25', slot: 'DAY', overrideReason: 'late entry' } });
    expect(editOperation({ opId: u(8), ownerId: 'me', roll: roll([], { sessionVersion: null }), marks: [] }).baseVersion).toBeNull();
  });
});

describe('mark all present', () => {
  const students = effectiveStudents(
    roll([
      student(1),
      student(2, { suggestedStatus: 'EXCUSED' }),
      student(3, { record: { status: 'ABSENT', minutesLate: null, version: 1, recordedBy: null } }),
    ]),
    [],
  );
  it('marks only the unmarked, with the parent\'s hint where there is one, and never touches a mark', () => {
    expect(markAllPresentMarks(students)).toEqual([
      { studentId: u(101), status: 'PRESENT' },
      { studentId: u(102), status: 'EXCUSED' },
    ]);
    expect(excusedByHint(students)).toBe(1);
  });
  it('is nothing when everybody is marked', () => {
    const all = effectiveStudents(roll([student(1)]), [pending(1, [mark(1, 'PRESENT')])]);
    expect(markAllPresentMarks(all)).toEqual([]);
  });
});

describe('counts', () => {
  it('counts every status and the unmarked', () => {
    const counts = countStatuses([{ status: 'PRESENT' }, { status: 'PRESENT' }, { status: 'ABSENT' }, { status: 'LATE' }, { status: 'EXCUSED' }, { status: 'LEFT_EARLY' }, { status: null }]);
    expect(counts).toEqual({ present: 2, absent: 1, late: 1, excused: 1, leftEarly: 1, unmarked: 1, total: 7 });
  });
});

describe('the lock and what may be edited', () => {
  const locksAt = '2026-11-26T21:00:00Z';
  const at = (iso: string) => Date.parse(iso);

  it('is locked when the API said so or when the lock time has passed (also offline)', () => {
    expect(isRollLocked({ locked: true, locksAt: null }, 0)).toBe(true);
    expect(isRollLocked({ locked: false, locksAt }, at('2026-11-26T20:59:59Z'))).toBe(false);
    expect(isRollLocked({ locked: false, locksAt }, at('2026-11-26T21:00:00Z'))).toBe(true);
    expect(isRollLocked({ locked: false, locksAt: null }, at('2030-01-01T00:00:00Z'))).toBe(false);
  });

  it('a locked roll can be edited only by who may override', () => {
    const now = at('2026-11-27T00:00:00Z');
    expect(editability(roll([], { locksAt }), 'fresh', now)).toEqual({ kind: 'locked' });
    expect(editability(roll([], { locksAt, mayEditLocked: true }), 'fresh', now)).toEqual({ kind: 'override' });
    expect(editability(roll([], { locksAt }), 'fresh', at('2026-11-25T08:00:00Z'))).toEqual({ kind: 'open' });
  });

  it('a cache that is too old is shown, not edited', () => {
    expect(editability(roll([]), 'stale', 0)).toEqual({ kind: 'stale' });
    const day = 24 * 3_600_000;
    expect(rollTrust(1000, 1000 + 2 * day, false, true)).toBe('cached');
    expect(rollTrust(1000, 1000 + 4 * day, false, true)).toBe('stale');
    // A roll built from the class list may be a little older.
    expect(rollTrust(1000, 1000 + 5 * day, true, true)).toBe('cached');
    expect(rollTrust(1000, 1000 + 8 * day, true, true)).toBe('stale');
    expect(rollTrust(0, 99 * day, false, false)).toBe('fresh');
  });

  it('says how old the data is', () => {
    expect(ageOf(1000, 1000 + 30_000)).toEqual({ unit: 'now', value: 0 });
    expect(ageOf(0, 5 * 60_000)).toEqual({ unit: 'minutes', value: 5 });
    expect(ageOf(0, 3 * 3_600_000 + 1)).toEqual({ unit: 'hours', value: 3 });
    expect(ageOf(0, 2 * 24 * 3_600_000)).toEqual({ unit: 'days', value: 2 });
    expect(ageOf(10, 5)).toEqual({ unit: 'now', value: 0 });
  });
});

describe('synthetic roll and server state', () => {
  it('is the class list with nobody marked, no hints, no version', () => {
    const made = syntheticRoll({
      schoolId: SCHOOL,
      classId: CLASS,
      date: '2026-11-25',
      slot: 'DAY',
      slots: ['DAY'],
      timeZone: 'Africa/Addis_Ababa',
      roster: [{ id: u(101), name: 'Abel', avatarMediaId: null, avatarColor: null }],
    });
    expect(made).toMatchObject({ synthetic: true, sessionVersion: null, locked: false, mayEditLocked: false });
    expect(made.students[0]).toMatchObject({ record: null, suggestedStatus: null, absence: null });
  });

  it('takes the stored records and the roll version from a result, so the screen does not fall back', () => {
    const before = roll([student(1), student(2)]);
    const after = applyServerState(before, conflictState(1, 'ABSENT'));
    expect(after.sessionVersion).toBe(7);
    expect(after.students[0]!.record).toEqual({ status: 'ABSENT', minutesLate: null, version: 2, recordedBy: u(555) });
    expect(after.students[1]!.record).toBeNull();
    expect(applyServerState(before, {})).toBe(before);
  });
});
