import {
  daysInEthiopianMonth,
  daysInGregorianMonth,
  ethiopianToGregorian,
  gregorianToEthiopian,
  PAGUME,
} from '@school/calendar';

import { addDays, eachDay, parseIso, toIso, weekdayOf, type IsoDate } from './civil';
import type { CalendarPreference } from './format-instant';

/**
 * The calendar's date maths in both calendars. Every date is a Gregorian `YYYY-MM-DD` key; the Ethiopian calendar (twelve months
 * of thirty days and a thirteenth of five or six, `@school/calendar`) only decides which keys make a month and what number a
 * cell shows. Nothing here reads the clock or the phone's zone: "today" is passed in.
 */

export type CalendarView = 'month' | 'week' | 'agenda';

/** A month of the display calendar. */
export interface MonthRef {
  year: number;
  month: number;
}

export interface DayCell {
  /** The Gregorian key of the day. */
  date: IsoDate;
  /** Whether the day belongs to the month shown (the grid is padded with neighbours to whole weeks). */
  inMonth: boolean;
  /** The day number in the display calendar. */
  day: number;
  /** The month number in the display calendar (a neighbour's, for padding). */
  month: number;
  year: number;
  weekday: number;
}

/** The week starts on this weekday (0 = Sunday). The API's repeat rules count Monday first, so the app does too. */
export const DEFAULT_WEEK_START = 1;

export function monthOf(date: IsoDate, calendar: CalendarPreference): MonthRef {
  const ymd = parseIso(date);
  if (!ymd) return { year: 1970, month: 1 };
  if (calendar === 'gregorian') return { year: ymd.year, month: ymd.month };
  const eth = gregorianToEthiopian(ymd);
  return { year: eth.year, month: eth.month };
}

export function dayInCalendar(date: IsoDate, calendar: CalendarPreference): { year: number; month: number; day: number } {
  const ymd = parseIso(date) ?? { year: 1970, month: 1, day: 1 };
  return calendar === 'gregorian' ? ymd : gregorianToEthiopian(ymd);
}

export function monthLength(ref: MonthRef, calendar: CalendarPreference): number {
  return calendar === 'gregorian' ? daysInGregorianMonth(ref.year, ref.month) : daysInEthiopianMonth(ref.year, ref.month);
}

/** The Gregorian key of the first day of a month. */
export function monthStart(ref: MonthRef, calendar: CalendarPreference): IsoDate {
  return calendar === 'gregorian' ? toIso({ year: ref.year, month: ref.month, day: 1 }) : toIso(ethiopianToGregorian({ year: ref.year, month: ref.month, day: 1 }));
}

export function monthEnd(ref: MonthRef, calendar: CalendarPreference): IsoDate {
  return addDays(monthStart(ref, calendar), monthLength(ref, calendar) - 1);
}

/** Months per year: 12 in the Gregorian calendar, 13 in the Ethiopian one (Pagume). */
export function monthsPerYear(calendar: CalendarPreference): number {
  return calendar === 'gregorian' ? 12 : PAGUME;
}

/** `ref` moved by whole months (negative: earlier), across year ends and through Pagume. */
export function shiftMonth(ref: MonthRef, delta: number, calendar: CalendarPreference): MonthRef {
  const perYear = monthsPerYear(calendar);
  const index = ref.year * perYear + (ref.month - 1) + delta;
  return { year: Math.floor(index / perYear), month: (((index % perYear) + perYear) % perYear) + 1 };
}

/** The weekday columns in order, e.g. [1,2,3,4,5,6,0] for a Monday start. */
export function weekdayOrder(weekStart: number): number[] {
  return Array.from({ length: 7 }, (_, i) => (weekStart + i) % 7);
}

/** The first day of the week (per `weekStart`) that holds `date`. */
export function weekStartOf(date: IsoDate, weekStart: number): IsoDate {
  const back = (weekdayOf(date) - weekStart + 7) % 7;
  return addDays(date, -back);
}

/** The seven days of the week holding `date`. */
export function weekOf(date: IsoDate, weekStart: number = DEFAULT_WEEK_START): IsoDate[] {
  const first = weekStartOf(date, weekStart);
  return Array.from({ length: 7 }, (_, i) => addDays(first, i));
}

function cellOf(date: IsoDate, calendar: CalendarPreference, ref: MonthRef): DayCell {
  const shown = dayInCalendar(date, calendar);
  return {
    date,
    inMonth: shown.year === ref.year && shown.month === ref.month,
    day: shown.day,
    month: shown.month,
    year: shown.year,
    weekday: weekdayOf(date),
  };
}

/**
 * The weeks of a month, each a row of seven days: the month padded before and after to whole weeks. A Gregorian month has
 * four to six rows, an Ethiopian month five or six, and Pagume (five or six days) one or two.
 */
export function monthGrid(ref: MonthRef, calendar: CalendarPreference, weekStart: number = DEFAULT_WEEK_START): DayCell[][] {
  const first = monthStart(ref, calendar);
  const last = monthEnd(ref, calendar);
  const gridFirst = weekStartOf(first, weekStart);
  const gridLast = addDays(weekStartOf(last, weekStart), 6);
  const days = eachDay(gridFirst, gridLast);
  const rows: DayCell[][] = [];
  for (let i = 0; i < days.length; i += 7) rows.push(days.slice(i, i + 7).map((date) => cellOf(date, calendar, ref)));
  return rows;
}

export interface DateRange {
  first: IsoDate;
  last: IsoDate;
}

/** The days a month's grid shows (what to fetch for it). */
export function gridRange(ref: MonthRef, calendar: CalendarPreference, weekStart: number = DEFAULT_WEEK_START): DateRange {
  const rows = monthGrid(ref, calendar, weekStart);
  return { first: rows[0]![0]!.date, last: rows[rows.length - 1]![6]!.date };
}

export const AGENDA_STEP_DAYS = 30;

/** What each view shows around `anchor`: a month grid, a week, or the next days as a list. */
export function rangeFor(view: CalendarView, anchor: IsoDate, calendar: CalendarPreference, weekStart: number = DEFAULT_WEEK_START, agendaDays = AGENDA_STEP_DAYS): DateRange {
  if (view === 'month') return gridRange(monthOf(anchor, calendar), calendar, weekStart);
  if (view === 'week') {
    const days = weekOf(anchor, weekStart);
    return { first: days[0]!, last: days[6]! };
  }
  return { first: anchor, last: addDays(anchor, agendaDays - 1) };
}

/** The anchor after stepping a view by `delta` pages (months, weeks, or agenda pages). */
export function stepAnchor(view: CalendarView, anchor: IsoDate, delta: number, calendar: CalendarPreference, agendaDays = AGENDA_STEP_DAYS): IsoDate {
  if (view === 'week') return addDays(anchor, 7 * delta);
  if (view === 'agenda') return addDays(anchor, agendaDays * delta);
  const next = shiftMonth(monthOf(anchor, calendar), delta, calendar);
  const length = monthLength(next, calendar);
  // Keep the day of the month when the new month has it, otherwise its last day.
  const day = Math.min(dayInCalendar(anchor, calendar).day, length);
  return addDays(monthStart(next, calendar), day - 1);
}

/** The ranges worth fetching ahead of time while the person looks at `anchor` (the previous and the next page). */
export function neighbouringRanges(view: CalendarView, anchor: IsoDate, calendar: CalendarPreference, weekStart: number = DEFAULT_WEEK_START, agendaDays = AGENDA_STEP_DAYS): DateRange[] {
  return [-1, 1].map((delta) => rangeFor(view, stepAnchor(view, anchor, delta, calendar, agendaDays), calendar, weekStart, agendaDays));
}

export function sameRange(a: DateRange, b: DateRange): boolean {
  return a.first === b.first && a.last === b.last;
}
