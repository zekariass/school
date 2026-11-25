/**
 * Attendance dates are the school's local calendar dates (the roll answers with its `timeZone`), never the
 * browser's date. Everything here is pure: the clock and the zone are arguments.
 */

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** True for a real calendar date "yyyy-mm-dd" ("2026-02-30" is not). */
export function isIsoDate(value: string | null | undefined): value is string {
  if (!value || !ISO_DATE.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

/** The calendar date at `now` in the IANA zone `timeZone` ("2026-11-25"); an unknown zone falls back to UTC. */
export function localDateIn(timeZone: string, now: Date): string {
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(now);
  } catch {
    return now.toISOString().slice(0, 10);
  }
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

/** `date` moved by `days` calendar days (negative goes back). Calendar arithmetic: no time zone, no DST. */
export function addDays(date: string, days: number): string {
  if (!isIsoDate(date)) return date;
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Whole days from `a` to `b` (positive when b is later). */
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
}

/** True when the school's date `date` has not started yet at `now` (the API refuses such a roll). */
export function isFutureDate(date: string, timeZone: string, now: Date): boolean {
  return date > localDateIn(timeZone, now);
}

/** True when "today" in the school's zone is `date` (used to label the day). */
export const isToday = (date: string, timeZone: string, now: Date): boolean => date === localDateIn(timeZone, now);

/** The default report range: the last `days` days up to and including today in the school's zone. */
export function defaultRange(timeZone: string, now: Date, days = 30): { from: string; to: string } {
  const to = localDateIn(timeZone, now);
  return { from: addDays(to, -(days - 1)), to };
}
