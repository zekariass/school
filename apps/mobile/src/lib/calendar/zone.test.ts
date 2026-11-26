import { describe, expect, it } from 'vitest';

import { compactDate, eachDay, expandCompactDate, parseIso, toIso, weekdayOf } from './civil';
import { clockIn, dateIn, dayEnd, dayStart, instantOf, offsetMinutes, usableZone, zoneLabel, zonedParts, zonesDiffer } from './zone';

const ADDIS = 'Africa/Addis_Ababa';
const BERLIN = 'Europe/Berlin';
const NEW_YORK = 'America/New_York';

describe('civil dates', () => {
  it('parses, writes and finds weekdays', () => {
    expect(parseIso('2026-11-26')).toEqual({ year: 2026, month: 11, day: 26 });
    expect(parseIso('2026-02-30')).toBeNull();
    expect(toIso({ year: 2026, month: 3, day: 9 })).toBe('2026-03-09');
    expect(weekdayOf('2026-11-29')).toBe(0);
  });
  it('lists days, bounded', () => {
    expect(eachDay('2026-02-27', '2026-03-02')).toEqual(['2026-02-27', '2026-02-28', '2026-03-01', '2026-03-02']);
    expect(eachDay('2026-03-02', '2026-02-27')).toEqual([]);
    expect(eachDay('2026-01-01', '2030-01-01', 10)).toHaveLength(10);
  });
  it('converts the compact UNTIL date', () => {
    expect(compactDate('2027-12-31')).toBe('20271231');
    expect(expandCompactDate('20271231')).toBe('2027-12-31');
    expect(expandCompactDate('20271331')).toBeNull();
    expect(expandCompactDate('20271231T000000Z')).toBeNull();
  });
});

describe('time zones', () => {
  it('reads the date of an instant in the zone given, never the phone', () => {
    // 23:30 UTC is already the next day in Addis Ababa (UTC+3) and still the same day in New York.
    const instant = Date.UTC(2026, 10, 26, 23, 30);
    expect(dateIn(instant, ADDIS)).toBe('2026-11-27');
    expect(dateIn(instant, NEW_YORK)).toBe('2026-11-26');
    expect(zonedParts(instant, ADDIS)).toEqual({ date: '2026-11-27', minutes: 2 * 60 + 30 });
    expect(zonedParts(Date.UTC(2026, 10, 26, 21, 0), ADDIS).minutes).toBe(0);
  });
  it('knows offsets and whether two zones show different clocks', () => {
    const noon = Date.UTC(2026, 10, 26, 12, 0);
    expect(offsetMinutes(noon, ADDIS)).toBe(180);
    expect(offsetMinutes(noon, NEW_YORK)).toBe(-300);
    expect(offsetMinutes(Date.UTC(2026, 6, 1), NEW_YORK)).toBe(-240);
    expect(zonesDiffer(ADDIS, NEW_YORK, noon)).toBe(true);
    expect(zonesDiffer(ADDIS, ADDIS, noon)).toBe(false);
    // Two names, one clock (Nairobi is also UTC+3).
    expect(zonesDiffer(ADDIS, 'Africa/Nairobi', noon)).toBe(false);
    expect(zonesDiffer(ADDIS, null, noon)).toBe(false);
  });
  it('falls back to UTC for a zone this phone does not know', () => {
    expect(usableZone('Mars/Olympus_Mons')).toBe('UTC');
    expect(usableZone(null)).toBe('UTC');
    expect(usableZone(ADDIS)).toBe(ADDIS);
    expect(dateIn(Date.UTC(2026, 10, 26, 23, 30), 'Mars/Olympus_Mons')).toBe('2026-11-26');
  });
  it('turns a wall-clock time into the instant, like the API', () => {
    expect(new Date(instantOf('2026-12-07', 9 * 60, ADDIS)).toISOString()).toBe('2026-12-07T06:00:00.000Z');
    expect(new Date(instantOf('2026-12-07', 9 * 60, NEW_YORK)).toISOString()).toBe('2026-12-07T14:00:00.000Z');
  });
  it('handles the clock changes of a daylight-saving zone', () => {
    // Berlin springs forward on 2026-03-29: 02:30 does not exist and moves on to 03:30 (CEST, 01:30Z).
    expect(new Date(instantOf('2026-03-29', 2 * 60 + 30, BERLIN)).toISOString()).toBe('2026-03-29T01:30:00.000Z');
    expect(new Date(instantOf('2026-03-29', 3 * 60 + 30, BERLIN)).toISOString()).toBe('2026-03-29T01:30:00.000Z');
    // ... and falls back on 2026-10-25: 02:30 happens twice and the first one (CEST, 00:30Z) is taken.
    expect(new Date(instantOf('2026-10-25', 2 * 60 + 30, BERLIN)).toISOString()).toBe('2026-10-25T00:30:00.000Z');
  });
  it('gives days of 23, 24 and 25 hours around a clock change', () => {
    const hours = (date: string) => (dayEnd(date, BERLIN) - dayStart(date, BERLIN)) / 3_600_000;
    expect(hours('2026-03-29')).toBe(23);
    expect(hours('2026-10-25')).toBe(25);
    expect(hours('2026-06-10')).toBe(24);
    expect((dayEnd('2026-12-07', ADDIS) - dayStart('2026-12-07', ADDIS)) / 3_600_000).toBe(24);
  });
  it('writes the time of day in the zone of the event, and names zones for people', () => {
    expect(clockIn(Date.UTC(2026, 11, 7, 6, 0), ADDIS, 'en')).toMatch(/^9:00\s?AM$/i);
    expect(clockIn(Date.UTC(2026, 11, 7, 6, 0), NEW_YORK, 'en')).toMatch(/^1:00\s?AM$/i);
    expect(zoneLabel('Africa/Addis_Ababa')).toBe('Africa/Addis Ababa');
  });
});
