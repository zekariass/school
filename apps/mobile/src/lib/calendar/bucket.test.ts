import { describe, expect, it } from 'vitest';

import { bucketItems, compareEntries, daysWithEntries, entryTime, isAllDayOnThisDay, itemSpan, type DayEntry } from './bucket';
import type { CalendarItem } from './types';

const ADDIS = 'Africa/Addis_Ababa';

let counter = 0;
function item(over: Partial<CalendarItem>): CalendarItem {
  counter += 1;
  return {
    id: `00000000-0000-4000-8000-${String(counter).padStart(12, '0')}`,
    source: 'EVENT',
    kind: 'SCHOOL_EVENT',
    title: `Item ${counter}`,
    allDay: false,
    startAt: '2026-12-07T06:00:00Z',
    endAt: '2026-12-07T07:00:00Z',
    timeZone: ADDIS,
    status: 'SCHEDULED',
    ...over,
  };
}

const RANGE = { first: '2026-11-30', last: '2026-12-13' };

describe('placing items on days', () => {
  it('puts a timed item on its day in ITS OWN zone, not the phone zone', () => {
    // 21:30Z is 00:30 the next day in Addis Ababa.
    const late = item({ startAt: '2026-12-06T21:30:00Z', endAt: '2026-12-06T22:30:00Z' });
    expect(itemSpan(late)).toEqual({ first: '2026-12-07', last: '2026-12-07' });
    const same = item({ startAt: '2026-12-06T21:30:00Z', endAt: '2026-12-06T22:30:00Z', timeZone: 'America/New_York' });
    expect(itemSpan(same)).toEqual({ first: '2026-12-06', last: '2026-12-06' });
    const days = bucketItems([late], RANGE);
    expect(days.get('2026-12-07')).toHaveLength(1);
    expect(days.get('2026-12-06')).toHaveLength(0);
  });

  it('puts an all-day item on its inclusive dates, never on the end-exclusive instant', () => {
    // Three days: the instants run to the start of the 4th day, but the dates say the 1st to the 3rd.
    const holiday = item({
      source: 'HOLIDAY',
      kind: 'PUBLIC',
      allDay: true,
      startDate: '2026-12-01',
      endDate: '2026-12-03',
      startAt: '2026-11-30T21:00:00Z',
      endAt: '2026-12-03T21:00:00Z',
    });
    expect(itemSpan(holiday)).toEqual({ first: '2026-12-01', last: '2026-12-03' });
    const days = bucketItems([holiday], RANGE);
    expect(daysWithEntries(days)).toEqual(['2026-12-01', '2026-12-02', '2026-12-03']);
    const entries = [...days.values()].flat();
    expect(entries.map((entry) => [entry.dayIndex, entry.dayCount, entry.first, entry.last])).toEqual([
      [0, 3, true, false],
      [1, 3, false, false],
      [2, 3, false, true],
    ]);
  });

  it('keeps a timed item that ends exactly at midnight on one day, and spreads one that runs past it', () => {
    const toMidnight = item({ startAt: '2026-12-07T15:00:00Z', endAt: '2026-12-07T21:00:00Z' }); // 18:00 to 00:00 in Addis
    expect(itemSpan(toMidnight)).toEqual({ first: '2026-12-07', last: '2026-12-07' });
    const overnight = item({ startAt: '2026-12-07T15:00:00Z', endAt: '2026-12-09T03:00:00Z' }); // 18:00 on the 7th to 06:00 on the 9th
    expect(itemSpan(overnight)).toEqual({ first: '2026-12-07', last: '2026-12-09' });
    const entries = [...bucketItems([overnight], RANGE).values()].flat();
    expect(entries.map((entry) => entryTime(entry).kind)).toEqual(['from', 'continues', 'until']);
    expect(isAllDayOnThisDay(entries[1]!)).toBe(true);
    expect(isAllDayOnThisDay(entries[0]!)).toBe(false);
  });

  it('shows a zero-length item on its start day', () => {
    const point = item({ startAt: '2026-12-07T06:00:00Z', endAt: '2026-12-07T06:00:00Z' });
    expect(itemSpan(point)).toEqual({ first: '2026-12-07', last: '2026-12-07' });
    expect(entryTime([...bucketItems([point], RANGE).values()].flat()[0]!)).toMatchObject({ kind: 'range', endMs: null });
  });

  it('places items correctly on the day of a clock change', () => {
    const berlin = 'Europe/Berlin';
    // All-day on 2026-03-29 (a 23-hour day): instants 2026-03-28T23:00Z to 2026-03-29T22:00Z, dates the 29th only.
    const day = item({ allDay: true, startDate: '2026-03-29', endDate: '2026-03-29', startAt: '2026-03-28T23:00:00Z', endAt: '2026-03-29T22:00:00Z', timeZone: berlin });
    // A 01:30 event that day and a 23:30 one.
    const early = item({ startAt: '2026-03-29T00:30:00Z', endAt: '2026-03-29T01:00:00Z', timeZone: berlin });
    const late = item({ startAt: '2026-03-29T21:30:00Z', endAt: '2026-03-29T21:50:00Z', timeZone: berlin });
    const days = bucketItems([day, early, late], { first: '2026-03-28', last: '2026-03-30' });
    expect(days.get('2026-03-29')).toHaveLength(3);
    expect(days.get('2026-03-28')).toHaveLength(0);
    expect(days.get('2026-03-30')).toHaveLength(0);
    // The 25-hour day of the autumn change: 23:30 on the 25th is still the 25th.
    const autumn = item({ startAt: '2026-10-25T22:30:00Z', endAt: '2026-10-25T22:45:00Z', timeZone: berlin });
    expect(itemSpan(autumn)).toEqual({ first: '2026-10-25', last: '2026-10-25' });
  });

  it('bounds a long item by the range instead of expanding it', () => {
    const year = item({ source: 'ACADEMIC_PERIOD', kind: 'YEAR_START', allDay: true, startDate: '2026-01-01', endDate: '2026-12-31' });
    const days = bucketItems([year], RANGE);
    expect(daysWithEntries(days)).toHaveLength(14);
    expect([...days.values()].flat()[0]).toMatchObject({ dayIndex: 333, dayCount: 365 });
  });

  it('drops items outside the range (the fetch window is padded)', () => {
    const before = item({ startAt: '2026-11-29T06:00:00Z', endAt: '2026-11-29T07:00:00Z' });
    const after = item({ startAt: '2026-12-14T06:00:00Z', endAt: '2026-12-14T07:00:00Z' });
    expect(daysWithEntries(bucketItems([before, after], RANGE))).toEqual([]);
  });

  it('keeps cancelled items (they are shown struck through) and recurring occurrences apart', () => {
    const base = '00000000-0000-4000-8000-0000000000aa';
    const first = item({ id: `${base}@20261207T060000Z`, eventId: base, occurrenceId: '20261207T060000Z', recurring: true });
    const second = item({
      id: `${base}@20261208T060000Z`,
      eventId: base,
      occurrenceId: '20261208T060000Z',
      recurring: true,
      status: 'CANCELLED',
      startAt: '2026-12-08T06:00:00Z',
      endAt: '2026-12-08T07:00:00Z',
    });
    const days = bucketItems([first, second], RANGE);
    expect(days.get('2026-12-07')).toHaveLength(1);
    expect(days.get('2026-12-08')![0]!.item.status).toBe('CANCELLED');
  });

  it('sorts a day: all-day first (holidays, school dates, events, personal), then by start time, then by title', () => {
    const entries = bucketItems(
      [
        item({ title: 'B late', startAt: '2026-12-07T09:00:00Z', endAt: '2026-12-07T10:00:00Z' }),
        item({ title: 'A early', startAt: '2026-12-07T05:00:00Z', endAt: '2026-12-07T06:00:00Z' }),
        item({ title: 'Mine', source: 'PERSONAL_EVENT', allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }),
        item({ title: 'Sports day', allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }),
        item({ title: 'Term starts', source: 'ACADEMIC_PERIOD', kind: 'PERIOD_START', allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }),
        item({ title: 'Feast', source: 'HOLIDAY', kind: 'PUBLIC', allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' }),
      ],
      RANGE,
    ).get('2026-12-07')!;
    expect(entries.map((entry) => entry.item.title)).toEqual(['Feast', 'Term starts', 'Sports day', 'Mine', 'A early', 'B late']);
  });

  it('describes the time to show for an entry', () => {
    const [entry] = bucketItems([item({})], RANGE).get('2026-12-07')! as [DayEntry];
    expect(entryTime(entry)).toEqual({ kind: 'range', startMs: Date.parse('2026-12-07T06:00:00Z'), endMs: Date.parse('2026-12-07T07:00:00Z') });
    const allDay = bucketItems([item({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-07' })], RANGE).get('2026-12-07')![0]!;
    expect(entryTime(allDay)).toEqual({ kind: 'allDay' });
    expect(compareEntries(allDay, entry)).toBeLessThan(0);
  });

  it('falls back to the instants for an all-day item without dates and ignores an item without times', () => {
    const noDates = item({ allDay: true, startAt: '2026-12-06T21:00:00Z', endAt: '2026-12-07T21:00:00Z' });
    expect(itemSpan(noDates)).toEqual({ first: '2026-12-07', last: '2026-12-07' });
    expect(itemSpan(item({ startAt: 'not a time' }))).toBeNull();
  });
});
