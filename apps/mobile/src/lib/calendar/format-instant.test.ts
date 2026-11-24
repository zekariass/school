import { describe, expect, it } from 'vitest';

import { computeClockSkewMs, defaultCalendarFor, formatInstant, isCalendarPreference } from './format-instant';

// 2025-09-11T09:30:00Z is Ethiopian New Year (Meskerem 1, 2018) at 12:30 in Addis Ababa (UTC+3).
const NEW_YEAR = '2025-09-11T09:30:00Z';

describe('formatInstant', () => {
  it('formats Gregorian dates in the given time zone', () => {
    const text = formatInstant(NEW_YEAR, { locale: 'en', calendar: 'gregorian', timeZone: 'Africa/Addis_Ababa' });
    expect(text).toContain('September 11, 2025');
    expect(text).toMatch(/12:30/);
  });

  it('formats Ethiopian dates with the English transliteration', () => {
    const text = formatInstant(NEW_YEAR, { locale: 'en', calendar: 'ethiopian' });
    expect(text.startsWith('Meskerem 1, 2018 E.C., ')).toBe(true);
    expect(text).toMatch(/12:30/);
  });

  it('formats Ethiopian dates in Amharic', () => {
    const text = formatInstant(new Date(NEW_YEAR), { locale: 'am', calendar: 'ethiopian' });
    expect(text.startsWith('መስከረም 1 ቀን 2018 ዓ.ም.')).toBe(true);
  });

  it('uses the time zone for the civil date', () => {
    // 22:30 UTC on Sep 10 is already Sep 11 in Addis Ababa.
    const addis = formatInstant('2025-09-10T22:30:00Z', { locale: 'en', calendar: 'ethiopian', timeZone: 'Africa/Addis_Ababa' });
    const utc = formatInstant('2025-09-10T22:30:00Z', { locale: 'en', calendar: 'ethiopian', timeZone: 'UTC' });
    expect(addis).toContain('Meskerem 1, 2018');
    expect(utc).toContain('Pagume 5, 2017');
  });

  it('returns an empty string for invalid input', () => {
    expect(formatInstant('not a date', { locale: 'en', calendar: 'gregorian' })).toBe('');
  });
});

describe('calendar preference helpers', () => {
  it('defaults to Ethiopian for Amharic only', () => {
    expect(defaultCalendarFor('am')).toBe('ethiopian');
    expect(defaultCalendarFor('en')).toBe('gregorian');
  });

  it('validates stored values', () => {
    expect(isCalendarPreference('ethiopian')).toBe(true);
    expect(isCalendarPreference('julian')).toBe(false);
    expect(isCalendarPreference(null)).toBe(false);
  });
});

describe('computeClockSkewMs', () => {
  it('measures the server time against the request midpoint', () => {
    const start = Date.parse('2025-01-01T00:00:00.000Z');
    expect(computeClockSkewMs('2025-01-01T00:00:01.000Z', start, start + 200)).toBe(900);
    expect(computeClockSkewMs('2024-12-31T23:59:59.000Z', start, start + 200)).toBe(-1100);
  });
});
