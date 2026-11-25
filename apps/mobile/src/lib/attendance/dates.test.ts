import { describe, expect, it } from 'vitest';

import { addDays, canGoEarlier, canGoLater, isSelectableDate, localDateIn, MAX_DAYS_BACK, recentDates } from './dates';

describe('localDateIn: the school\'s date, not the device\'s', () => {
  // 2026-11-24 22:30 UTC is already the 25th in Addis Ababa (UTC+3), still the 24th in New York.
  const instant = Date.UTC(2026, 10, 24, 22, 30);

  it('follows the school time zone', () => {
    expect(localDateIn('Africa/Addis_Ababa', instant)).toBe('2026-11-25');
    expect(localDateIn('America/New_York', instant)).toBe('2026-11-24');
    expect(localDateIn('UTC', instant)).toBe('2026-11-24');
  });

  it('changes at local midnight, on both sides of it', () => {
    expect(localDateIn('Africa/Addis_Ababa', Date.UTC(2026, 10, 24, 20, 59, 59))).toBe('2026-11-24');
    expect(localDateIn('Africa/Addis_Ababa', Date.UTC(2026, 10, 24, 21, 0, 0))).toBe('2026-11-25');
  });

  it('is right around a daylight saving change in another zone', () => {
    // New York left daylight time on 2026-11-01 at 06:00 UTC.
    expect(localDateIn('America/New_York', Date.UTC(2026, 10, 1, 5, 30))).toBe('2026-11-01');
    expect(localDateIn('America/New_York', Date.UTC(2026, 10, 1, 4, 30))).toBe('2026-11-01');
    expect(localDateIn('America/New_York', Date.UTC(2026, 10, 2, 4, 59))).toBe('2026-11-01');
    expect(localDateIn('America/New_York', Date.UTC(2026, 10, 2, 5, 0))).toBe('2026-11-02');
  });

  it('falls back to UTC for a zone the device does not know', () => {
    expect(localDateIn('Not/AZone', instant)).toBe('2026-11-24');
  });
});

describe('addDays', () => {
  it('moves across months, years and leap days', () => {
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2027-02-28', 1)).toBe('2027-03-01');
  });
  it('leaves something that is not a date alone', () => {
    expect(addDays('soon', 1)).toBe('soon');
  });
});

describe('the date switcher', () => {
  const today = '2026-11-25';
  it('offers today and the days before it, never the future', () => {
    expect(isSelectableDate(today, today)).toBe(true);
    expect(isSelectableDate('2026-11-24', today)).toBe(true);
    expect(isSelectableDate('2026-11-26', today)).toBe(false);
    expect(isSelectableDate(addDays(today, -MAX_DAYS_BACK), today)).toBe(true);
    expect(isSelectableDate(addDays(today, -MAX_DAYS_BACK - 1), today)).toBe(false);
    expect(isSelectableDate('nonsense', today)).toBe(false);
  });
  it('knows its ends', () => {
    expect(canGoLater(today, today)).toBe(false);
    expect(canGoLater('2026-11-24', today)).toBe(true);
    expect(canGoEarlier(addDays(today, -MAX_DAYS_BACK), today)).toBe(false);
    expect(canGoEarlier(today, today)).toBe(true);
  });
  it('lists recent dates newest first', () => {
    expect(recentDates(today, 3)).toEqual(['2026-11-25', '2026-11-24', '2026-11-23']);
    expect(recentDates(today, 100)).toHaveLength(MAX_DAYS_BACK + 1);
  });
});
