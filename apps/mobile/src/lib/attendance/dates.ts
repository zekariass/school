import { isCivilDate } from '@/lib/sync/operations';

/**
 * Attendance dates are the school's calendar dates (`timeZone` of the roll), never the device's: a phone set to
 * another zone, or travelling, must not take a roll on the wrong day. Plain `YYYY-MM-DD` strings, compared as text.
 */

const FALLBACK_ZONE = 'UTC';

/** The calendar date at `nowMs` in `timeZone` (UTC when the zone is unknown to the device). */
export function localDateIn(timeZone: string, nowMs: number): string {
  for (const zone of [timeZone, FALLBACK_ZONE]) {
    try {
      const parts = new Intl.DateTimeFormat('en-US', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(nowMs));
      const get = (type: string) => parts.find((part) => part.type === type)?.value;
      const year = get('year');
      const month = get('month');
      const day = get('day');
      if (year && month && day) return `${year.padStart(4, '0')}-${month}-${day}`;
    } catch {
      // try the fallback zone
    }
  }
  return new Date(nowMs).toISOString().slice(0, 10);
}

/** `date` moved by `days` (negative: earlier). Returns the input when it is not a date. */
export function addDays(date: string, days: number): string {
  if (!isCivilDate(date)) return date;
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

/** Whole days from `from` to `to` (negative when `to` is earlier). */
export function daysBetween(from: string, to: string): number {
  if (!isCivilDate(from) || !isCivilDate(to)) return Number.NaN;
  const [fy, fm, fd] = from.split('-').map(Number) as [number, number, number];
  const [ty, tm, td] = to.split('-').map(Number) as [number, number, number];
  return Math.round((Date.UTC(ty, tm - 1, td) - Date.UTC(fy, fm - 1, fd)) / 86_400_000);
}

/** How far back the date switcher goes. The API refuses only the future; older rolls are locked for most users. */
export const MAX_DAYS_BACK = 14;

/** A date the switcher offers: not after the school's today, at most {@link MAX_DAYS_BACK} days before it. */
export function isSelectableDate(date: string, today: string): boolean {
  return isCivilDate(date) && isCivilDate(today) && date <= today && date >= addDays(today, -MAX_DAYS_BACK);
}

export function canGoEarlier(date: string, today: string): boolean {
  return isSelectableDate(addDays(date, -1), today);
}

export function canGoLater(date: string, today: string): boolean {
  return isSelectableDate(addDays(date, 1), today);
}

/** Today's date, then the days before it, newest first: what the date strip shows. */
export function recentDates(today: string, count = 7): string[] {
  return Array.from({ length: Math.min(count, MAX_DAYS_BACK + 1) }, (_, index) => addDays(today, -index));
}
