import { addDays, parseIso, toIso, type IsoDate } from './civil';

/**
 * Time-zone arithmetic for the calendar, all of it explicit: every function takes the zone, none ever reads the phone's.
 * Only `Intl.DateTimeFormat` with `timeZone` + `formatToParts` is used (the same subset the attendance and feed schedule code
 * relies on, available on Hermes); no `Intl.PluralRules`, `Intl.ListFormat` or `Intl.RelativeTimeFormat`.
 */

const zoneKnown = new Map<string, boolean>();

/** The zone the device knows: the given one, or UTC when this phone's runtime has never heard of it (checked once per zone). */
export function usableZone(timeZone: string | null | undefined): string {
  if (!timeZone) return 'UTC';
  let known = zoneKnown.get(timeZone);
  if (known === undefined) {
    try {
      new Intl.DateTimeFormat('en-US', { timeZone });
      known = true;
    } catch {
      known = false;
    }
    zoneKnown.set(timeZone, known);
  }
  return known ? timeZone : 'UTC';
}

const DAY_MS = 86_400_000;
const formatters = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(zone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(zone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
    formatters.set(zone, formatter);
  }
  return formatter;
}

export interface ZonedParts {
  date: IsoDate;
  /** Minutes after local midnight, 0..1439. */
  minutes: number;
}

/** The wall-clock date and time of an instant in a zone (UTC when this phone's runtime does not know the zone). */
export function zonedParts(ms: number, timeZone: string): ZonedParts {
  const zone = usableZone(timeZone);
  const parts = partsFormatter(zone).formatToParts(new Date(ms));
  const get = (type: string) => Number(parts.find((part) => part.type === type)?.value ?? 0);
  return {
    date: toIso({ year: get('year'), month: get('month'), day: get('day') }),
    minutes: (get('hour') % 24) * 60 + get('minute'),
  };
}

/** The calendar date of an instant in a zone. */
export function dateIn(ms: number, timeZone: string): IsoDate {
  return zonedParts(ms, timeZone).date;
}

/** Minutes the zone is ahead of UTC at that instant (whole minutes). */
export function offsetMinutes(ms: number, timeZone: string): number {
  const { date, minutes } = zonedParts(ms, timeZone);
  const ymd = parseIso(date);
  if (!ymd) return 0;
  const local = Date.UTC(ymd.year, ymd.month - 1, ymd.day) / 60_000 + minutes;
  return Math.round(local - Math.floor(ms / 60_000));
}

/** Whether the two zones show different clocks at that moment (the "your phone is in another zone" note). */
export function zonesDiffer(a: string | null | undefined, b: string | null | undefined, atMs: number): boolean {
  if (!a || !b) return false;
  if (a === b) return false;
  return offsetMinutes(atMs, a) !== offsetMinutes(atMs, b);
}

/**
 * The instant of a wall-clock date and time in a zone. When clocks go back the time happens twice and the first one is taken;
 * a time that does not exist (spring forward) moves onward, as the clocks do. Same rule as the API (`ZonedDateTime.of`).
 */
export function instantOf(date: IsoDate, minutes: number, timeZone: string): number {
  const zone = usableZone(timeZone);
  const ymd = parseIso(date);
  if (!ymd) return Number.NaN;
  const wall = Date.UTC(ymd.year, ymd.month - 1, ymd.day, Math.floor(minutes / 60), minutes % 60);
  // The zone's offset a day before and a day after the wall time: at most one transition lies between them.
  const before = offsetMinutes(wall - DAY_MS, zone) * 60_000;
  const after = offsetMinutes(wall + DAY_MS, zone) * 60_000;
  const candidates = [...new Set([wall - before, wall - after])].filter((instant) => offsetMinutes(instant, zone) * 60_000 === wall - instant);
  return candidates.length > 0 ? Math.min(...candidates) : wall - before;
}

/** The instant a calendar day starts in a zone (23 to 25 hours before the next day starts, around a clock change). */
export function dayStart(date: IsoDate, timeZone: string): number {
  return instantOf(date, 0, timeZone);
}

/** The instant the day after `date` starts: the exclusive end of the day. */
export function dayEnd(date: IsoDate, timeZone: string): number {
  return dayStart(addDays(date, 1), timeZone);
}

const clockFormatters = new Map<string, Intl.DateTimeFormat>();

/**
 * The time of day of an instant in a zone, in the language's usual style ("9:30 AM", "09:30"). Falls back to 24-hour digits when
 * the runtime cannot format it.
 */
export function clockIn(ms: number, timeZone: string, locale: string): string {
  const zone = usableZone(timeZone);
  const key = `${locale}|${zone}`;
  try {
    let formatter = clockFormatters.get(key);
    if (!formatter) {
      formatter = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit', timeZone: zone });
      clockFormatters.set(key, formatter);
    }
    return formatter.format(new Date(ms));
  } catch {
    const { minutes } = zonedParts(ms, zone);
    return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
  }
}

/** `Africa/Addis_Ababa` as "Africa/Addis Ababa" for a person. */
export function zoneLabel(timeZone: string): string {
  return timeZone.replace(/_/g, ' ');
}
