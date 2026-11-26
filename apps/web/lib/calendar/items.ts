import type { components } from "@school/api-client";
import { addDays, compareIso, diffDays, isoInZone } from "./dates";
import type { DateRange } from "./grid";

export type CalendarItem = components["schemas"]["CalendarItem"];

/** The sources of the union (`GET /calendar`); later ones (assignments, sessions) are shown with a generic look. */
export type SourceId = "EVENT" | "PERSONAL_EVENT" | "HOLIDAY" | "ACADEMIC_PERIOD";
export const SOURCE_IDS: readonly SourceId[] = ["EVENT", "PERSONAL_EVENT", "HOLIDAY", "ACADEMIC_PERIOD"];

export function isKnownSource(source: string): source is SourceId {
  return (SOURCE_IDS as readonly string[]).includes(source);
}

/** The filter chips and the sources they switch: events, holidays, academic dates and personal events. */
export type FilterId = "events" | "holidays" | "academic" | "personal";
export const FILTER_IDS: readonly FilterId[] = ["events", "holidays", "academic", "personal"];
export const FILTER_SOURCE: Record<FilterId, SourceId> = {
  events: "EVENT",
  holidays: "HOLIDAY",
  academic: "ACADEMIC_PERIOD",
  personal: "PERSONAL_EVENT",
};

/**
 * The `sources` parameter for the chips that are on. Always explicit: the API leaves a person's own events out of a
 * school's or a child's calendar unless asked, and "all four" must mean all four.
 */
export function sourcesParam(on: ReadonlySet<FilterId>): string {
  return FILTER_IDS.filter((id) => on.has(id))
    .map((id) => FILTER_SOURCE[id])
    .join(",");
}

/** Cancelled events and occurrences stay in the calendar, struck through. */
export function isCancelled(item: Pick<CalendarItem, "status">): boolean {
  return item.status === "CANCELLED";
}

/** True for items that open the event detail (the others are holidays and academic dates: nothing more to read). */
export function isEventItem(item: Pick<CalendarItem, "source" | "eventId">): boolean {
  return (item.source === "EVENT" || item.source === "PERSONAL_EVENT") && !!item.eventId;
}

/** Items of the union are all-day by dates and timed by instants. */
export interface DaySpan {
  /** First and last day (inclusive), as dates in the item's own time zone. */
  first: string;
  last: string;
}

/**
 * The days an item occupies, always in the item's own time zone. All-day items carry the dates themselves (`endAt` is
 * end-exclusive: never use it for them when the dates are there); timed items are placed by their instants, so an event
 * from 23:00 to 01:00 touches two days, one ending exactly at midnight only the first, and the 23-hour and 25-hour days of a
 * daylight-saving change are just days.
 */
export function daySpan(item: Pick<CalendarItem, "allDay" | "startDate" | "endDate" | "startAt" | "endAt" | "timeZone">): DaySpan {
  if (item.allDay && item.startDate) {
    const last = item.endDate && item.endDate >= item.startDate ? item.endDate : item.startDate;
    return { first: item.startDate, last };
  }
  const first = isoInZone(item.startAt, item.timeZone);
  const start = new Date(item.startAt).getTime();
  const end = new Date(item.endAt).getTime();
  const last = end > start ? isoInZone(new Date(end - 1), item.timeZone) : first;
  return { first, last: last < first ? first : last };
}

/** One item on one day of its span. */
export interface DayEntry {
  item: CalendarItem;
  day: string;
  /** Position in the span: 0 on the first day. */
  index: number;
  /** Number of days of the item (1 for most). */
  days: number;
  startsToday: boolean;
  endsToday: boolean;
}

/**
 * Places items on the days of a range (inclusive). A multi-day item appears on each of its days inside the range; the
 * order within a day is all-day and multi-day items first, then timed ones by start, then by id.
 */
export function bucketItems(items: readonly CalendarItem[], range: DateRange): Map<string, DayEntry[]> {
  const out = new Map<string, DayEntry[]>();
  for (const item of items) {
    const span = daySpan(item);
    if (span.last < range.from || span.first > range.to) continue;
    const days = diffDays(span.first, span.last) + 1;
    const from = compareIso(span.first, range.from) < 0 ? range.from : span.first;
    const to = compareIso(span.last, range.to) > 0 ? range.to : span.last;
    for (let day = from, i = diffDays(span.first, from); day <= to; day = addDays(day, 1), i++) {
      const list = out.get(day) ?? [];
      list.push({ item, day, index: i, days, startsToday: i === 0, endsToday: i === days - 1 });
      out.set(day, list);
    }
  }
  for (const list of out.values()) list.sort(compareEntries);
  return out;
}

function compareEntries(a: DayEntry, b: DayEntry): number {
  const wide = (e: DayEntry) => (e.item.allDay || e.days > 1 ? 0 : 1);
  return wide(a) - wide(b) || compareIso(a.item.startAt, b.item.startAt) || compareIso(a.item.id, b.item.id);
}

/** The entries of a day (empty when there are none). */
export function entriesOn(buckets: ReadonlyMap<string, DayEntry[]>, iso: string): DayEntry[] {
  return buckets.get(iso) ?? [];
}

/** Days with at least one entry, in order (the agenda lists these). */
export function busyDays(buckets: ReadonlyMap<string, DayEntry[]>): string[] {
  return [...buckets.keys()].sort(compareIso);
}

/** True when two time zones show the same wall clock at `instant` (so a note about the zone would only add noise). */
export function sameWallClock(a: string, b: string, instant: string | Date): boolean {
  if (a === b) return true;
  const date = typeof instant === "string" ? new Date(instant) : instant;
  const fmt = (timeZone: string) =>
    new Intl.DateTimeFormat("en-CA", { timeZone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date);
  try {
    return fmt(a) === fmt(b);
  } catch {
    return false;
  }
}

/** A timed item shown in a zone other than the viewer's gets a note (all-day items are dates: no zone to explain). */
export function needsZoneNote(item: Pick<CalendarItem, "allDay" | "timeZone" | "startAt">, viewerZone: string | null): boolean {
  if (item.allDay || !viewerZone) return false;
  return !sameWallClock(item.timeZone, viewerZone, item.startAt);
}

/** The window id of cached data: a stable key for one fetch. */
export interface CalendarScope {
  schoolId: string | null;
  studentId: string | null;
  sources: string;
}
