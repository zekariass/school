import { describe, expect, it } from 'vitest';

import {
  canGoEarlierStaff,
  canGoLaterStaff,
  checkMark,
  correctionFormFor,
  correctionRequest,
  countStaff,
  dayRequest,
  emptyMarkForm,
  isStaffDate,
  markInput,
  minutesOf,
  type StaffRecord,
} from './model';

const STAFF = '00000000-0000-4000-8000-000000000001';

const record = (overrides: Partial<StaffRecord> = {}): StaffRecord =>
  ({
    id: 'r1',
    staffId: STAFF,
    staffName: 'Abebe',
    date: '2026-11-25',
    method: 'MANUAL',
    recordedAt: '2026-11-25T06:00:00Z',
    recordedBy: STAFF,
    status: 'LATE',
    minutesLate: 15,
    note: 'Bus',
    version: 2,
    ...overrides,
  }) as StaffRecord;

describe('the date window', () => {
  it('stops at the school\'s today and at 62 days back', () => {
    const today = '2026-11-25';
    expect(isStaffDate(today, today)).toBe(true);
    expect(isStaffDate('2026-11-26', today)).toBe(false);
    expect(isStaffDate('2026-09-24', today)).toBe(true);
    expect(isStaffDate('2026-09-23', today)).toBe(false);
    expect(isStaffDate('nonsense', today)).toBe(false);
    expect(canGoLaterStaff(today, today)).toBe(false);
    expect(canGoLaterStaff('2026-11-24', today)).toBe(true);
    expect(canGoEarlierStaff('2026-09-24', today)).toBe(false);
    expect(canGoEarlierStaff('2026-09-25', today)).toBe(true);
  });
});

describe('marking a staff member', () => {
  it('reads minutes as a whole number from 1 to 600', () => {
    expect(minutesOf('15')).toBe(15);
    expect(minutesOf(' 600 ')).toBe(600);
    for (const bad of ['', '0', '601', '1.5', '-3', 'ten', '1000']) expect(minutesOf(bad)).toBeNull();
  });

  it('minutes matter only for LATE, and typed nonsense is flagged', () => {
    expect(checkMark({ status: 'PRESENT', minutes: '999' })).toEqual({ valid: true, minutesInvalid: false, minutesLate: null });
    expect(checkMark({ status: 'LATE', minutes: '20' })).toEqual({ valid: true, minutesInvalid: false, minutesLate: 20 });
    expect(checkMark({ status: 'LATE', minutes: '' })).toEqual({ valid: true, minutesInvalid: false, minutesLate: null });
    expect(checkMark({ status: 'LATE', minutes: '700' }).valid).toBe(false);
  });

  it('builds the mark: trimmed note of at most 200 characters, minutes only with LATE', () => {
    expect(markInput(STAFF, { ...emptyMarkForm })).toEqual({ staffId: STAFF, status: 'PRESENT' });
    expect(markInput(STAFF, { status: 'HALF_DAY', minutes: '30', note: '  doctor  ' })).toEqual({ staffId: STAFF, status: 'HALF_DAY', note: 'doctor' });
    expect(markInput(STAFF, { status: 'LATE', minutes: '30', note: '' })).toEqual({ staffId: STAFF, status: 'LATE', minutesLate: 30 });
    expect(markInput(STAFF, { status: 'PRESENT', minutes: '', note: 'x'.repeat(300) })!.note).toHaveLength(200);
    expect(markInput(STAFF, { status: 'LATE', minutes: '9999', note: '' })).toBeNull();
  });

  it('builds the day body: marks and/or "everybody else present", never an empty request', () => {
    const one = markInput(STAFF, emptyMarkForm)!;
    expect(dayRequest([one], false)).toEqual({ records: [one] });
    expect(dayRequest([], true)).toEqual({ markRestPresent: true });
    expect(dayRequest([one], true)).toEqual({ records: [one], markRestPresent: true });
    expect(dayRequest([], false)).toBeNull();
  });

  it('counts the day, with the unmarked ones', () => {
    const entries = [{ record: { status: 'PRESENT' } }, { record: { status: 'PRESENT' } }, { record: { status: 'LATE' } }, { record: { status: 'HALF_DAY' } }, { record: { status: 'EXCUSED' } }, { record: { status: 'ABSENT' } }, {}];
    expect(countStaff(entries as never)).toEqual({ present: 2, late: 1, halfDay: 1, excused: 1, absent: 1, unmarked: 1, total: 7 });
  });
});

describe('correcting a record', () => {
  it('starts from the record and asks for a reason', () => {
    const form = correctionFormFor(record());
    expect(form).toEqual({ status: 'LATE', minutes: '15', note: 'Bus', reason: '' });
    expect(correctionRequest(record(), form)).toEqual({ ok: false, problem: 'reason' });
    expect(correctionRequest(record(), { ...form, reason: '   ' })).toEqual({ ok: false, problem: 'reason' });
  });

  it('does not send a correction that changes nothing (the API would answer nothing_to_correct)', () => {
    const form = { ...correctionFormFor(record()), reason: 'Checked the gate book' };
    expect(correctionRequest(record(), form)).toEqual({ ok: false, problem: 'unchanged' });
  });

  it('sends the new status with the version read, the reason, and the note only when it changed', () => {
    const base = { ...correctionFormFor(record()), reason: ' Gate book says on time ' };
    const result = correctionRequest(record(), { ...base, status: 'PRESENT' });
    expect(result).toEqual({ ok: true, request: { status: 'PRESENT', reason: 'Gate book says on time', expectedVersion: 2 } });

    // Same status, a new note: absent keeps, text replaces, empty clears.
    expect(correctionRequest(record(), { ...base, note: 'Bus broke down' })).toEqual({
      ok: true,
      request: { status: 'LATE', minutesLate: 15, note: 'Bus broke down', reason: 'Gate book says on time', expectedVersion: 2 },
    });
    expect(correctionRequest(record(), { ...base, note: '' })).toMatchObject({ ok: true, request: { note: '' } });
  });

  it('carries new minutes with LATE, and refuses nonsense minutes', () => {
    const base = { ...correctionFormFor(record()), reason: 'Counted again' };
    expect(correctionRequest(record(), { ...base, minutes: '25' })).toMatchObject({ ok: true, request: { status: 'LATE', minutesLate: 25 } });
    expect(correctionRequest(record(), { ...base, minutes: '9999' })).toEqual({ ok: false, problem: 'minutes' });
  });

  it('limits the reason to 200 characters', () => {
    const form = { ...correctionFormFor(record()), status: 'ABSENT' as const, reason: 'r'.repeat(250) };
    const result = correctionRequest(record(), form);
    expect(result.ok && result.request.reason).toHaveLength(200);
  });

  it('a record without a version is corrected from version 0', () => {
    const noVersion = record({ version: undefined as never });
    const result = correctionRequest(noVersion, { ...correctionFormFor(noVersion), status: 'PRESENT', reason: 'x' });
    expect(result).toMatchObject({ ok: true, request: { expectedVersion: 0 } });
  });
});
