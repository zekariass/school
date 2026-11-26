import { addDays, daysBetween, type IsoDate } from './civil';
import type { DateRange } from './grid';

/** The API refuses a window longer than 366 days. */
export const MAX_WINDOW_DAYS = 366;

/**
 * Zones run from UTC-12 to UTC+14, so a calendar day anywhere starts at most 14 hours before and ends at most 12 hours after the
 * same-dated UTC day. The window is padded by 14 hours on both sides so an item of the first or last visible day in any zone
 * is included; the screens then place each item on its own day (`bucket.ts`) and drop what falls outside the range.
 */
const PAD_MS = 14 * 3_600_000;

export interface FetchWindow {
  /** ISO instants, `from` inclusive, `to` exclusive. */
  from: string;
  to: string;
}

function utcMidnight(date: IsoDate): number {
  return Date.parse(`${date}T00:00:00.000Z`);
}

/** The instants to ask the API for, for the days `first`..`last` (inclusive). Null when the range is empty or too long. */
export function fetchWindow(range: DateRange): FetchWindow | null {
  const days = daysBetween(range.first, range.last) + 1;
  if (!(days >= 1)) return null;
  const from = utcMidnight(range.first) - PAD_MS;
  const to = utcMidnight(addDays(range.last, 1)) + PAD_MS;
  if ((to - from) / 86_400_000 > MAX_WINDOW_DAYS) return null;
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString() };
}

/** Whether a range is small enough to be one request (a month grid is at most 42 days; agenda pages 30). */
export function windowFits(range: DateRange): boolean {
  return fetchWindow(range) !== null;
}
