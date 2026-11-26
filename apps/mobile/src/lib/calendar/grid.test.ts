import { describe, expect, it } from 'vitest';

import { eachDay, weekdayOf } from './civil';
import {
  DEFAULT_WEEK_START,
  dayInCalendar,
  gridRange,
  monthEnd,
  monthGrid,
  monthLength,
  monthOf,
  monthStart,
  neighbouringRanges,
  rangeFor,
  shiftMonth,
  stepAnchor,
  weekOf,
  weekStartOf,
  weekdayOrder,
} from './grid';
import { monthName, monthTitle, weekdayName, weekdayShort } from './names';

describe('week helpers', () => {
  it('lists the weekday columns from the week start', () => {
    expect(weekdayOrder(1)).toEqual([1, 2, 3, 4, 5, 6, 0]);
    expect(weekdayOrder(0)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    expect(weekdayOrder(6)).toEqual([6, 0, 1, 2, 3, 4, 5]);
  });
  it('finds the week of a date for a Monday and a Sunday start', () => {
    // 2026-11-26 is a Thursday.
    expect(weekdayOf('2026-11-26')).toBe(4);
    expect(weekOf('2026-11-26', 1)).toEqual(['2026-11-23', '2026-11-24', '2026-11-25', '2026-11-26', '2026-11-27', '2026-11-28', '2026-11-29']);
    expect(weekOf('2026-11-26', 0)[0]).toBe('2026-11-22');
    expect(weekStartOf('2026-11-29', 1)).toBe('2026-11-23');
    expect(weekStartOf('2026-11-29', 0)).toBe('2026-11-29');
    expect(weekOf('2026-11-26')[0]).toBe(weekOf('2026-11-26', DEFAULT_WEEK_START)[0]);
  });
});

describe('the Gregorian month grid', () => {
  it('pads a month to whole weeks, in the order of the week start', () => {
    // 1 November 2026 is a Sunday: with Monday first it ends the first row of 26 October.
    const monday = monthGrid({ year: 2026, month: 11 }, 'gregorian', 1);
    expect(monday).toHaveLength(6);
    expect(monday[0]![0]!.date).toBe('2026-10-26');
    expect(monday[0]![6]!.date).toBe('2026-11-01');
    expect(monday[5]![6]!.date).toBe('2026-12-06');
    expect(monday.every((row) => row.length === 7)).toBe(true);
    const sunday = monthGrid({ year: 2026, month: 11 }, 'gregorian', 0);
    expect(sunday).toHaveLength(5);
    expect(sunday[0]![0]!.date).toBe('2026-11-01');
    expect(sunday[4]![6]!.date).toBe('2026-12-05');
  });
  it('marks the days of the month and numbers every cell in the display calendar', () => {
    const grid = monthGrid({ year: 2026, month: 11 }, 'gregorian', 1).flat();
    expect(grid.filter((cell) => cell.inMonth)).toHaveLength(30);
    expect(grid[0]).toMatchObject({ date: '2026-10-26', inMonth: false, day: 26, month: 10 });
    expect(grid.find((cell) => cell.date === '2026-11-26')).toMatchObject({ inMonth: true, day: 26, weekday: 4 });
  });
  it('has four rows when February starts on the first day of the week', () => {
    expect(monthGrid({ year: 2026, month: 2 }, 'gregorian', 0)).toHaveLength(4);
  });
  it('knows leap years', () => {
    expect(monthLength({ year: 2028, month: 2 }, 'gregorian')).toBe(29);
    expect(monthLength({ year: 2026, month: 2 }, 'gregorian')).toBe(28);
    expect(monthLength({ year: 2100, month: 2 }, 'gregorian')).toBe(28);
    expect(monthEnd({ year: 2028, month: 2 }, 'gregorian')).toBe('2028-02-29');
  });
});

describe('the Ethiopian month grid', () => {
  it('finds the Ethiopian month of a Gregorian day', () => {
    expect(monthOf('2026-11-26', 'ethiopian')).toEqual({ year: 2019, month: 3 });
    expect(dayInCalendar('2026-11-26', 'ethiopian')).toEqual({ year: 2019, month: 3, day: 17 });
    expect(monthOf('2026-09-11', 'ethiopian')).toEqual({ year: 2019, month: 1 });
    expect(monthOf('2026-09-10', 'ethiopian')).toEqual({ year: 2018, month: 13 });
  });
  it('starts a month on the right Gregorian day and gives it thirty days', () => {
    expect(monthStart({ year: 2019, month: 3 }, 'ethiopian')).toBe('2026-11-10');
    expect(monthLength({ year: 2019, month: 3 }, 'ethiopian')).toBe(30);
    expect(monthEnd({ year: 2019, month: 3 }, 'ethiopian')).toBe('2026-12-09');
  });
  it('has a thirteenth month of five days, and six in a leap year', () => {
    expect(monthLength({ year: 2018, month: 13 }, 'ethiopian')).toBe(5);
    expect(monthLength({ year: 2019, month: 13 }, 'ethiopian')).toBe(6);
    expect(monthStart({ year: 2019, month: 13 }, 'ethiopian')).toBe('2027-09-06');
    expect(monthEnd({ year: 2019, month: 13 }, 'ethiopian')).toBe('2027-09-11');
    const grid = monthGrid({ year: 2019, month: 13 }, 'ethiopian', 1).flat();
    expect(grid.filter((cell) => cell.inMonth).map((cell) => cell.day)).toEqual([1, 2, 3, 4, 5, 6]);
    // The days around Pagume belong to the neighbouring months and are shown as such.
    expect(grid.filter((cell) => !cell.inMonth).every((cell) => cell.month !== 13 || cell.year !== 2019)).toBe(true);
    expect(monthGrid({ year: 2018, month: 13 }, 'ethiopian', 1).flat().filter((cell) => cell.inMonth)).toHaveLength(5);
  });
  it('shows one or two rows for Pagume', () => {
    expect(monthGrid({ year: 2018, month: 13 }, 'ethiopian', 1).length).toBeLessThanOrEqual(2);
  });
  it('numbers the cells in the Ethiopian calendar and keeps the Gregorian date as the key', () => {
    const cell = monthGrid({ year: 2019, month: 3 }, 'ethiopian', 1).flat().find((c) => c.date === '2026-11-26');
    expect(cell).toMatchObject({ day: 17, month: 3, year: 2019, inMonth: true });
  });
  it('is five or six full weeks for an ordinary month', () => {
    for (let month = 1; month <= 12; month++) {
      const rows = monthGrid({ year: 2019, month }, 'ethiopian', 1);
      expect(rows.length).toBeGreaterThanOrEqual(5);
      expect(rows.length).toBeLessThanOrEqual(6);
      expect(rows.flat().filter((cell) => cell.inMonth)).toHaveLength(30);
    }
  });
});

describe('moving between months', () => {
  it('crosses the year end in both calendars', () => {
    expect(shiftMonth({ year: 2026, month: 12 }, 1, 'gregorian')).toEqual({ year: 2027, month: 1 });
    expect(shiftMonth({ year: 2026, month: 1 }, -1, 'gregorian')).toEqual({ year: 2025, month: 12 });
    expect(shiftMonth({ year: 2026, month: 11 }, 14, 'gregorian')).toEqual({ year: 2028, month: 1 });
  });
  it('goes through the thirteenth month', () => {
    expect(shiftMonth({ year: 2019, month: 12 }, 1, 'ethiopian')).toEqual({ year: 2019, month: 13 });
    expect(shiftMonth({ year: 2019, month: 13 }, 1, 'ethiopian')).toEqual({ year: 2020, month: 1 });
    expect(shiftMonth({ year: 2019, month: 1 }, -1, 'ethiopian')).toEqual({ year: 2018, month: 13 });
    expect(shiftMonth({ year: 2019, month: 1 }, -14, 'ethiopian')).toEqual({ year: 2017, month: 13 });
  });
  it('keeps the day of the month when it can, and clamps it when it cannot', () => {
    expect(stepAnchor('month', '2026-01-31', 1, 'gregorian')).toBe('2026-02-28');
    expect(stepAnchor('month', '2026-03-15', -1, 'gregorian')).toBe('2026-02-15');
    // 30 Hidar 2019 (9 Dec 2026) in the Ethiopian calendar: next month is Tahsas, 30 days.
    expect(stepAnchor('month', '2026-12-09', 1, 'ethiopian')).toBe('2027-01-08');
    // Into Pagume, which has only 6 days: day 14 of Nehase clamps to day 6 of Pagume.
    expect(stepAnchor('month', '2027-08-20', 1, 'ethiopian')).toBe('2027-09-11');
  });
  it('steps weeks and agenda pages by days', () => {
    expect(stepAnchor('week', '2026-11-26', 1, 'gregorian')).toBe('2026-12-03');
    expect(stepAnchor('week', '2026-11-26', -2, 'ethiopian')).toBe('2026-11-12');
    expect(stepAnchor('agenda', '2026-11-26', 1, 'gregorian')).toBe('2026-12-26');
  });
});

describe('what each view shows and what to fetch ahead', () => {
  it('shows the grid of the month, a week, or the next days', () => {
    expect(rangeFor('month', '2026-11-26', 'gregorian', 1)).toEqual({ first: '2026-10-26', last: '2026-12-06' });
    expect(gridRange({ year: 2026, month: 11 }, 'gregorian', 1)).toEqual({ first: '2026-10-26', last: '2026-12-06' });
    expect(rangeFor('week', '2026-11-26', 'gregorian', 1)).toEqual({ first: '2026-11-23', last: '2026-11-29' });
    expect(rangeFor('agenda', '2026-11-26', 'gregorian', 1)).toEqual({ first: '2026-11-26', last: '2026-12-25' });
  });
  it('lists the previous and the next page to prefetch', () => {
    const [before, after] = neighbouringRanges('month', '2026-11-26', 'gregorian', 1);
    expect(before).toEqual(rangeFor('month', '2026-10-26', 'gregorian', 1));
    expect(after).toEqual(rangeFor('month', '2026-12-26', 'gregorian', 1));
    expect(neighbouringRanges('week', '2026-11-26', 'gregorian', 1)).toEqual([
      { first: '2026-11-16', last: '2026-11-22' },
      { first: '2026-11-30', last: '2026-12-06' },
    ]);
  });
  it('covers every day of a month grid in the fetched range', () => {
    const range = rangeFor('month', '2026-11-26', 'ethiopian', 1);
    const days = eachDay(range.first, range.last);
    expect(days).toContain('2026-11-10');
    expect(days).toContain('2026-12-09');
    expect(days.length % 7).toBe(0);
  });
});

describe('names', () => {
  it('names Gregorian and Ethiopian months and the weekdays in both languages', () => {
    expect(monthName('gregorian', 11, 'en')).toBe('November');
    expect(monthName('ethiopian', 3, 'en')).toBe('Hidar');
    expect(monthName('ethiopian', 13, 'en')).toBe('Pagume');
    expect(monthName('ethiopian', 13, 'am')).toBe('ጳጉሜን');
    expect(monthName('gregorian', 1, 'am')).toBe('ጃንዋሪ');
    expect(monthTitle('ethiopian', { year: 2019, month: 3 }, 'en')).toBe('Hidar 2019');
    expect(weekdayName(1, 'en')).toBe('Monday');
    expect(weekdayShort(1, 'en')).toBe('Mon');
    expect(weekdayName(1, 'am')).toBe('ሰኞ');
    expect(weekdayShort(2, 'am')).toBe('ማክ');
  });
});
