import { describe, expect, it } from 'vitest';

import { absenceDateBounds, canCancel, canDecide, initialAbsenceForm, reportStatus, toRequestBody, validateAbsenceForm, withFromDate, type AbsenceForm } from './form';

const today = '2026-11-25';
const form = (overrides: Partial<AbsenceForm> = {}): AbsenceForm => ({ ...initialAbsenceForm(today), ...overrides });

describe('validateAbsenceForm', () => {
  it('accepts a day, a week and the longest allowed stay', () => {
    expect(validateAbsenceForm(form(), today)).toEqual([]);
    expect(validateAbsenceForm(form({ fromDate: '2026-11-26', toDate: '2026-12-02' }), today)).toEqual([]);
    expect(validateAbsenceForm(form({ fromDate: '2026-11-25', toDate: '2027-01-24' }), today)).toEqual([]); // 61 days
  });

  it('refuses a range that is back to front or longer than 61 days', () => {
    expect(validateAbsenceForm(form({ fromDate: '2026-11-27', toDate: '2026-11-26' }), today)).toEqual(['order']);
    expect(validateAbsenceForm(form({ fromDate: '2026-11-25', toDate: '2027-01-25' }), today)).toEqual(['tooLong']); // 62 days
  });

  it('refuses reports too far back or ahead, with a day of slack for a device in another zone', () => {
    expect(validateAbsenceForm(form({ fromDate: '2026-11-18', toDate: '2026-11-18' }), today)).toEqual([]); // 7 back
    expect(validateAbsenceForm(form({ fromDate: '2026-11-17', toDate: '2026-11-17' }), today)).toEqual([]); // slack
    expect(validateAbsenceForm(form({ fromDate: '2026-11-16', toDate: '2026-11-16' }), today)).toEqual(['tooEarly']);
    expect(validateAbsenceForm(form({ fromDate: '2027-02-23', toDate: '2027-02-23' }), today)).toEqual([]); // 90 ahead
    expect(validateAbsenceForm(form({ fromDate: '2027-02-25', toDate: '2027-02-25' }), today)).toEqual(['tooFar']);
  });

  it('refuses a note that is too long', () => {
    expect(validateAbsenceForm(form({ note: 'x'.repeat(500) }), today)).toEqual([]);
    expect(validateAbsenceForm(form({ note: 'x'.repeat(501) }), today)).toEqual(['noteTooLong']);
  });
});

describe('the form', () => {
  it('starts today for illness', () => {
    expect(initialAbsenceForm(today)).toEqual({ fromDate: today, toDate: today, reasonCode: 'ILLNESS', note: '' });
  });
  it('moving the first day past the last drags the last along', () => {
    expect(withFromDate(form({ toDate: '2026-11-26' }), '2026-11-30')).toMatchObject({ fromDate: '2026-11-30', toDate: '2026-11-30' });
    expect(withFromDate(form({ toDate: '2026-12-05' }), '2026-11-30')).toMatchObject({ fromDate: '2026-11-30', toDate: '2026-12-05' });
  });
  it('sends the note only when there is one', () => {
    expect(toRequestBody(form({ note: '  ' }))).toEqual({ fromDate: today, toDate: today, reasonCode: 'ILLNESS' });
    expect(toRequestBody(form({ note: ' flu ' }))).toEqual({ fromDate: today, toDate: today, reasonCode: 'ILLNESS', note: 'flu' });
  });
  it('bounds the steppers', () => {
    expect(absenceDateBounds(today)).toEqual({ min: '2026-11-18', max: '2027-02-23' });
  });
});

describe('who may do what with a report', () => {
  it('the guardian withdraws a waiting or acknowledged report, the teacher decides a waiting one', () => {
    expect(['SUBMITTED', 'ACKNOWLEDGED', 'DECLINED', 'CANCELLED'].map(canCancel)).toEqual([true, true, false, false]);
    expect(['SUBMITTED', 'ACKNOWLEDGED', 'DECLINED', 'CANCELLED'].map(canDecide)).toEqual([true, false, false, false]);
    expect(reportStatus('ACKNOWLEDGED')).toBe('ACKNOWLEDGED');
    expect(reportStatus('NEW_THING')).toBe('UNKNOWN');
  });
});
