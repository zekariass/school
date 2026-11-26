import type { DateRange } from './grid';
import { CALENDAR_SOURCES, type CalendarSource } from './types';

/** Query keys of the calendar. Everything under this root is memory-only. */

export const CALENDAR_ROOT = 'calendar';

export interface CalendarFilter {
  /** Narrow to one school (null: every school of the caller). */
  schoolId: string | null;
  /** A guardian's view of one child (null: all children). */
  studentId: string | null;
  sources: readonly CalendarSource[];
}

export const DEFAULT_SOURCES: readonly CalendarSource[] = CALENDAR_SOURCES;

/** The sources in a fixed order, comma separated: the `sources` parameter, and part of the key. */
export function sourcesParam(sources: readonly CalendarSource[]): string {
  return CALENDAR_SOURCES.filter((source) => sources.includes(source)).join(',');
}

export const itemsKey = (filter: CalendarFilter, range: DateRange) =>
  [CALENDAR_ROOT, 'items', filter.schoolId ?? 'all', filter.studentId ?? 'all', sourcesParam(filter.sources), range.first, range.last] as const;
/** Whether two window keys differ only in the days they cover (same school, child and sources). */
export function sameFilter(a: readonly unknown[], b: readonly unknown[]): boolean {
  return a.length === b.length && a.slice(0, 5).every((part, index) => part === b[index]);
}
export const ITEMS_PREFIX = [CALENDAR_ROOT, 'items'] as const;
export const eventKey = (eventId: string) => [CALENDAR_ROOT, 'event', eventId] as const;
export const capabilitiesKey = (schoolId: string) => [CALENDAR_ROOT, 'capabilities', schoolId] as const;
export const classChoicesKey = (schoolId: string) => [CALENDAR_ROOT, 'classes', schoolId] as const;

/**
 * Nothing about the calendar is written to TanStack's persisted cache (SQLite): the titles, texts and places of events (a
 * parent meeting's title may name a child) and the lists of who answered what are personal data. They live in memory only
 * and are dropped at sign-out. The only thing the calendar persists is the Gregorian/Ethiopian display choice.
 */
export function isPersistableCalendarQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== CALENDAR_ROOT;
}

/** How long an unused page stays in memory (the app default is a day). */
export const CALENDAR_GC_MS = 5 * 60_000;
/** A fetched window counts as fresh for this long. */
export const CALENDAR_STALE_MS = 60_000;

/** A request page is at most this many items (the API's maximum) and a window follows at most this many pages. */
export const PAGE_LIMIT = 500;
export const MAX_PAGES = 4;
