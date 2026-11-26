import {
  civilDateInTimeZone,
  daysInEthiopianMonth,
  daysInGregorianMonth,
  ethiopianToGregorian,
  gregorianToEthiopian,
  gregorianToJdn,
  jdnToGregorian,
  type CivilDate,
} from "@school/calendar";

/**
 * Calendar-date arithmetic for the calendar views. Dates travel as "yyyy-mm-dd" (Gregorian, no time zone: the form the
 * API uses for all-day items); the Ethiopian calendar is a display of the same days via `@school/calendar`. Instants
 * become dates only with an explicit time zone: nothing here reads the browser's.
 */

export type CalendarSystem = "gregorian" | "ethiopian";

/** 0 = Sunday ... 6 = Saturday, as in `Date.getUTCDay()`. */
export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6;

/** The first day of the week in the grids: Monday, as in Ethiopia and in the API's recurrence rules. */
export const DEFAULT_WEEK_START: Weekday = 1;

const ISO = /^(\d{4})-(\d{2})-(\d{2})$/;

const two = (n: number) => String(n).padStart(2, "0");

export function toIso({ year, month, day }: CivilDate): string {
  return `${String(year).padStart(4, "0")}-${two(month)}-${two(day)}`;
}

/** A valid Gregorian "yyyy-mm-dd" as its parts, or null. */
export function parseIso(value: string | null | undefined): CivilDate | null {
  const match = ISO.exec(value ?? "");
  if (!match) return null;
  const date = { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
  if (date.month < 1 || date.month > 12 || date.day < 1 || date.day > daysInGregorianMonth(date.year, date.month)) return null;
  return date;
}

export function isIsoDate(value: unknown): value is string {
  return typeof value === "string" && parseIso(value) !== null;
}

function jdnOf(iso: string): number {
  const date = parseIso(iso);
  if (!date) throw new RangeError(`Invalid date: ${iso}`);
  return gregorianToJdn(date);
}

export function addDays(iso: string, days: number): string {
  return toIso(jdnToGregorian(jdnOf(iso) + days));
}

/** Whole days from `a` to `b` (positive when `b` is later). */
export function diffDays(a: string, b: string): number {
  return jdnOf(b) - jdnOf(a);
}

/** ISO dates sort as text. */
export function compareIso(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function weekdayOf(iso: string): Weekday {
  return ((jdnOf(iso) + 1) % 7) as Weekday;
}

/** The days from `first` to `last`, both included. */
export function eachDay(first: string, last: string): string[] {
  const out: string[] = [];
  const n = diffDays(first, last);
  for (let i = 0; i <= n; i++) out.push(addDays(first, i));
  return out;
}

/** The calendar date of an instant in an explicit time zone. */
export function isoInZone(instant: string | Date, timeZone: string): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  return toIso(civilDateInTimeZone(date, timeZone));
}

// --- display calendars -------------------------------------------------------------------

export function daysInMonth(system: CalendarSystem, year: number, month: number): number {
  return system === "ethiopian" ? daysInEthiopianMonth(year, month) : daysInGregorianMonth(year, month);
}

export function monthsInYear(system: CalendarSystem): number {
  return system === "ethiopian" ? 13 : 12;
}

/** A Gregorian day as year, month and day in the display calendar. */
export function toDisplay(iso: string, system: CalendarSystem): CivilDate {
  const date = parseIso(iso);
  if (!date) throw new RangeError(`Invalid date: ${iso}`);
  return system === "ethiopian" ? gregorianToEthiopian(date) : date;
}

export function fromDisplay(date: CivilDate, system: CalendarSystem): string {
  return toIso(system === "ethiopian" ? ethiopianToGregorian(date) : date);
}

export interface MonthBounds {
  year: number;
  month: number;
  /** First and last day of the month, as Gregorian ISO dates. */
  first: string;
  last: string;
  days: number;
}

/** The month (of the display calendar) that holds `iso`. */
export function monthBounds(iso: string, system: CalendarSystem): MonthBounds {
  const d = toDisplay(iso, system);
  const days = daysInMonth(system, d.year, d.month);
  return {
    year: d.year,
    month: d.month,
    days,
    first: fromDisplay({ year: d.year, month: d.month, day: 1 }, system),
    last: fromDisplay({ year: d.year, month: d.month, day: days }, system),
  };
}

/** The same day of the month `delta` months away, or the last day when that month is shorter (Pagume has 5 or 6). */
export function shiftMonth(iso: string, system: CalendarSystem, delta: number): string {
  const d = toDisplay(iso, system);
  const per = monthsInYear(system);
  const index = d.year * per + (d.month - 1) + delta;
  const year = Math.floor(index / per);
  const month = (((index % per) + per) % per) + 1;
  const day = Math.min(d.day, daysInMonth(system, year, month));
  return fromDisplay({ year, month, day }, system);
}

/** The 7 days of the week that holds `iso`, from `weekStart`. */
export function weekOf(iso: string, weekStart: Weekday): string[] {
  const lead = (weekdayOf(iso) - weekStart + 7) % 7;
  const first = addDays(iso, -lead);
  return Array.from({ length: 7 }, (_, i) => addDays(first, i));
}
