import { describe, expect, it } from 'vitest';

import { CLASS, SCHOOL, u } from '@/lib/sync/test-support';
import type { ConflictEntry, QueuedAttendance } from '@/lib/sync/types';

import { markFromEntry, resolveConflict, sameMark } from './conflicts';

const entry = (n: number, stored: ConflictEntry['stored']['status'], submitted: ConflictEntry['submitted']['status'], extra: Partial<ConflictEntry['submitted']> = {}): ConflictEntry => ({
  studentId: u(100 + n),
  stored: { status: stored, minutesLate: null, version: 2 + n, recordedBy: u(555) },
  submitted: { status: submitted, minutesLate: null, ...extra },
});

const op = (entries: ConflictEntry[]): QueuedAttendance => ({
  type: 'ATTENDANCE_MARK',
  opId: u(1),
  ownerId: 'me',
  schoolId: SCHOOL,
  classId: CLASS,
  baseVersion: 3,
  createdAt: 0,
  attempts: 1,
  nextAttemptAt: 0,
  status: 'CONFLICT',
  payload: { date: '2026-11-25', slot: 'DAY', records: [] },
  conflict: { sessionVersion: 9, entries },
});

describe('resolveConflict', () => {
  it('"keep theirs" forgets the entry and sends nothing', () => {
    const result = resolveConflict(op([entry(1, 'ABSENT', 'PRESENT'), entry(2, 'LATE', 'PRESENT')]), [u(101)], 'keep-theirs', u(50));
    expect(result.resend).toBeUndefined();
    expect(result.remaining?.entries.map((e) => e.studentId)).toEqual([u(102)]);
  });

  it('"use mine" resends the mark as a NEW operation that names the version of the record just seen', () => {
    const result = resolveConflict(op([entry(1, 'ABSENT', 'LATE', { minutesLate: 15 })]), [u(101)], 'use-mine', u(50));
    expect(result.remaining).toBeNull();
    expect(result.resend).toEqual({
      type: 'ATTENDANCE_MARK',
      opId: u(50),
      ownerId: 'me',
      schoolId: SCHOOL,
      classId: CLASS,
      baseVersion: 9,
      payload: { date: '2026-11-25', slot: 'DAY', records: [{ studentId: u(101), status: 'LATE', minutesLate: 15, expectedVersion: 3 }] },
    });
    // A new id: the old operation is finished (a repeat of its id would only return the old conflict).
    expect(result.resend!.opId).not.toBe(u(1));
  });

  it('settles several students at once and keeps the others', () => {
    const result = resolveConflict(op([entry(1, 'ABSENT', 'PRESENT'), entry(2, 'ABSENT', 'PRESENT'), entry(3, 'ABSENT', 'PRESENT')]), [u(101), u(103)], 'use-mine', u(50));
    expect(result.resend?.type === 'ATTENDANCE_MARK' && result.resend.payload.records.map((r) => r.studentId)).toEqual([u(101), u(103)]);
    expect(result.remaining?.entries.map((e) => e.studentId)).toEqual([u(102)]);
  });

  it('does nothing for a student that is not in the conflict, or an operation without one', () => {
    expect(resolveConflict(op([entry(1, 'ABSENT', 'PRESENT')]), [u(199)], 'use-mine', u(50)).resend).toBeUndefined();
    expect(resolveConflict({ ...op([]), conflict: undefined }, [u(101)], 'use-mine', u(50)).remaining).toBeNull();
  });

  it('minutes only travel with LATE', () => {
    expect(markFromEntry(entry(1, 'ABSENT', 'ABSENT', { minutesLate: 5 }))).toEqual({ studentId: u(101), status: 'ABSENT', expectedVersion: 3 });
  });

  it('knows when both teachers said the same thing', () => {
    expect(sameMark(entry(1, 'ABSENT', 'ABSENT'))).toBe(true);
    expect(sameMark(entry(1, 'ABSENT', 'LATE'))).toBe(false);
  });
});
