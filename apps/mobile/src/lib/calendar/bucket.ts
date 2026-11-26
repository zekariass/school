import { daysBetween, eachDay, maxDate, minDate, type IsoDate } from './civil';
import type { DateRange } from './grid';
import type { CalendarItem } from './types';
import { dateIn } from './zone';

/**
 * Puts the items of the union on days. The days are the item's own: an all-day item covers its inclusive `startDate`..`endDate`
 * (`endAt` is end-exclusive, so it is never used for these), a timed item the days of its instants in ITS time zone (an
 * evening class in Addis Ababa is on that evening's day on a phone set to New York). A timed item that ends exactly at midnight
 * does not spill onto the next day; one that ends after midnight is on both.
 */

export interface DayEntry {
  /** Unique per item and day (a multi-day item has one entry per day). */
  key: string;
  item: CalendarItem;
  date: IsoDate;
  /** 0-based position of this day within the item, and how many days it covers. */
  dayIndex: number;
  dayCount: number;
  first: boolean;
  last: boolean;
}

/** The first and last day of an item in its own zone; null when the item has no usable times. */
export function itemSpan(item: CalendarItem): DateRange | null {
  if (item.allDay && item.startDate && item.endDate) {
    return item.endDate >= item.startDate ? { first: item.startDate, last: item.endDate } : { first: item.startDate, last: item.startDate };
  }
  const start = Date.parse(item.startAt);
  const end = Date.parse(item.endAt);
  if (Number.isNaN(start)) return null;
  const first = dateIn(start, item.timeZone);
  if (Number.isNaN(end) || end <= start) return { first, last: first };
  const last = dateIn(end - 1, item.timeZone);
  return { first, last: last >= first ? last : first };
}

const SOURCE_RANK: Record<string, number> = { HOLIDAY: 0, ACADEMIC_PERIOD: 1, EVENT: 2, PERSONAL_EVENT: 3 };

/** All-day first (holidays, academic dates, events, personal), then by start time, then by title. */
export function compareEntries(a: DayEntry, b: DayEntry): number {
  const aAllDay = isAllDayOnThisDay(a);
  const bAllDay = isAllDayOnThisDay(b);
  if (aAllDay !== bAllDay) return aAllDay ? -1 : 1;
  if (aAllDay) {
    const rank = (SOURCE_RANK[a.item.source] ?? 9) - (SOURCE_RANK[b.item.source] ?? 9);
    if (rank !== 0) return rank;
  } else {
    const start = Date.parse(a.item.startAt) - Date.parse(b.item.startAt);
    if (start !== 0 && !Number.isNaN(start)) return start;
  }
  return a.item.title.localeCompare(b.item.title) || a.item.id.localeCompare(b.item.id);
}

/** True for all-day items and for the middle days of a timed item that runs past midnight (nothing to show a time for). */
export function isAllDayOnThisDay(entry: DayEntry): boolean {
  return entry.item.allDay || (entry.dayCount > 1 && !entry.first && !entry.last);
}

/**
 * The entries of every day of `range`, each day sorted for display. Days outside the range are dropped (the fetch window is
 * padded). An item may have an entry on each of its days; a year-long item is bounded by the range, not expanded to a year.
 */
export function bucketItems(items: readonly CalendarItem[], range: DateRange): Map<IsoDate, DayEntry[]> {
  const days = new Map<IsoDate, DayEntry[]>();
  for (const date of eachDay(range.first, range.last)) days.set(date, []);
  for (const item of items) {
    const span = itemSpan(item);
    if (!span) continue;
    const from = maxDate(span.first, range.first);
    const to = minDate(span.last, range.last);
    if (to < from) continue;
    const dayCount = daysBetween(span.first, span.last) + 1;
    for (const date of eachDay(from, to)) {
      const dayIndex = daysBetween(span.first, date);
      days.get(date)?.push({ key: `${item.id}|${date}`, item, date, dayIndex, dayCount, first: dayIndex === 0, last: dayIndex === dayCount - 1 });
    }
  }
  for (const entries of days.values()) entries.sort(compareEntries);
  return days;
}

export type EntryTime =
  | { kind: 'allDay' }
  /** A timed item inside one day: start, and the end when it has a length. */
  | { kind: 'range'; startMs: number; endMs: number | null }
  /** The first day of a timed item that runs past midnight. */
  | { kind: 'from'; startMs: number }
  /** The last day of such an item. */
  | { kind: 'until'; endMs: number }
  /** A middle day of it. */
  | { kind: 'continues' };

/** What time to show for an entry on its day. */
export function entryTime(entry: DayEntry): EntryTime {
  const { item } = entry;
  if (item.allDay) return { kind: 'allDay' };
  const startMs = Date.parse(item.startAt);
  const endMs = Date.parse(item.endAt);
  if (entry.dayCount <= 1) return { kind: 'range', startMs, endMs: endMs > startMs ? endMs : null };
  if (entry.first) return { kind: 'from', startMs };
  if (entry.last) return { kind: 'until', endMs };
  return { kind: 'continues' };
}

/** The days of a bucket that hold at least one entry, in order. */
export function daysWithEntries(days: Map<IsoDate, DayEntry[]>): IsoDate[] {
  return [...days.entries()].filter(([, entries]) => entries.length > 0).map(([date]) => date);
}
