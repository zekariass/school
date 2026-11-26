import { describe, expect, it } from 'vitest';

import {
  canStepDate,
  clockText,
  defaultPick,
  fromInstant,
  scheduleProblem,
  stepDate,
  stepMinutes,
  toInstant,
  usableZone,
} from './schedule';

const ADDIS = 'Africa/Addis_Ababa'; // UTC+3, no daylight saving
const NEW_YORK = 'America/New_York';

describe('toInstant', () => {
  it('reads the wall clock in the school zone, whatever the phone says', () => {
    expect(toInstant({ date: '2026-12-01', minutes: 7 * 60 + 30 }, ADDIS).toISOString()).toBe('2026-12-01T04:30:00.000Z');
    expect(toInstant({ date: '2026-12-01', minutes: 7 * 60 + 30 }, 'UTC').toISOString()).toBe('2026-12-01T07:30:00.000Z');
  });
  it('follows daylight saving of the school zone', () => {
    expect(toInstant({ date: '2026-01-15', minutes: 8 * 60 }, NEW_YORK).toISOString()).toBe('2026-01-15T13:00:00.000Z');
    expect(toInstant({ date: '2026-07-15', minutes: 8 * 60 }, NEW_YORK).toISOString()).toBe('2026-07-15T12:00:00.000Z');
  });
  it('moves a time that does not exist (spring forward) onward, like the clocks', () => {
    // 2026-03-08 02:30 does not exist in New York: clocks jump from 02:00 to 03:00.
    const at = toInstant({ date: '2026-03-08', minutes: 2 * 60 + 30 }, NEW_YORK);
    expect(fromInstant(at, NEW_YORK)).toEqual({ date: '2026-03-08', minutes: 3 * 60 + 30 });
  });
  it('takes the first of a time that happens twice (clocks go back)', () => {
    // 2026-11-01 01:30 happens twice in New York: 05:30Z (EDT) and 06:30Z (EST).
    expect(toInstant({ date: '2026-11-01', minutes: 90 }, NEW_YORK).toISOString()).toBe('2026-11-01T05:30:00.000Z');
  });
  it('falls back to UTC for a zone the phone does not know', () => {
    expect(usableZone('Mars/Olympus')).toBe('UTC');
    expect(usableZone(undefined)).toBe('UTC');
    expect(toInstant({ date: '2026-12-01', minutes: 60 }, 'Mars/Olympus').toISOString()).toBe('2026-12-01T01:00:00.000Z');
  });
});

describe('fromInstant', () => {
  it('is the inverse, across midnight', () => {
    const pick = fromInstant('2026-12-01T22:30:00Z', ADDIS);
    expect(pick).toEqual({ date: '2026-12-02', minutes: 60 + 30 });
    expect(toInstant(pick, ADDIS).toISOString()).toBe('2026-12-01T22:30:00.000Z');
  });
});

describe('defaultPick', () => {
  it('is at least an hour away, on the half hour, in school time', () => {
    const now = Date.parse('2026-12-01T05:10:00Z'); // 08:10 in Addis
    expect(defaultPick(now, ADDIS)).toEqual({ date: '2026-12-01', minutes: 9 * 60 + 30 });
  });
  it('rolls over midnight', () => {
    const now = Date.parse('2026-12-01T19:40:00Z'); // 22:40 in Addis
    expect(defaultPick(now, ADDIS)).toEqual({ date: '2026-12-02', minutes: 0 });
    expect(defaultPick(Date.parse('2026-12-01T20:40:00Z'), ADDIS)).toEqual({ date: '2026-12-02', minutes: 60 });
  });
});

describe('scheduleProblem', () => {
  const now = Date.parse('2026-12-01T05:00:00Z');
  it('refuses the past and the next minute', () => {
    expect(scheduleProblem({ date: '2026-12-01', minutes: 8 * 60 }, ADDIS, now)).toBe('past'); // 05:00Z = 08:00 Addis
    expect(scheduleProblem({ date: '2026-12-01', minutes: 8 * 60 + 30 }, ADDIS, now)).toBeNull();
  });
  it('refuses more than 90 days ahead', () => {
    expect(scheduleProblem({ date: '2027-03-01', minutes: 600 }, ADDIS, now)).toBe('too_far');
    expect(scheduleProblem({ date: '2027-02-20', minutes: 600 }, ADDIS, now)).toBeNull();
  });
  it('refuses nonsense', () => {
    expect(scheduleProblem({ date: '2026-13-40', minutes: 0 }, ADDIS, now)).toBe('invalid');
    expect(scheduleProblem({ date: '2026-12-02', minutes: 2000 }, ADDIS, now)).toBe('invalid');
  });
  it('judges "today" by the school, not the phone', () => {
    // 22:00Z on Dec 1 is already Dec 2, 01:00 in Addis.
    const lateNow = Date.parse('2026-12-01T22:00:00Z');
    expect(canStepDate({ date: '2026-12-02', minutes: 600 }, -1, ADDIS, lateNow)).toBe(false);
    expect(canStepDate({ date: '2026-12-02', minutes: 600 }, 1, ADDIS, lateNow)).toBe(true);
  });
});

describe('stepping', () => {
  it('moves the date by days', () => {
    expect(stepDate({ date: '2026-12-31', minutes: 5 }, 1)).toEqual({ date: '2027-01-01', minutes: 5 });
  });
  it('moves the time and wraps into the neighbouring day', () => {
    expect(stepMinutes({ date: '2026-12-01', minutes: 23 * 60 + 50 }, 1)).toEqual({ date: '2026-12-02', minutes: 5 });
    expect(stepMinutes({ date: '2026-12-01', minutes: 5 }, -1)).toEqual({ date: '2026-11-30', minutes: 23 * 60 + 50 });
    expect(stepMinutes({ date: '2026-12-01', minutes: 60 }, 1, 30)).toEqual({ date: '2026-12-01', minutes: 90 });
  });
  it('writes the time with two digits', () => {
    expect(clockText(7 * 60 + 5)).toBe('07:05');
    expect(clockText(0)).toBe('00:00');
  });
});
