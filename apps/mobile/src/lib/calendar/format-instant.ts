import { formatEthiopianDate, gregorianToEthiopian, instantToEthiopian } from '@school/calendar';
import { isDisplayCalendar, type DisplayCalendar } from '@school/validation/country';

/** The calendars the app can draw; a country offers some of them (`offeredCalendars`, D42). */
export type CalendarPreference = DisplayCalendar;

/** kv-store key of the saved calendar display preference. */
export const CALENDAR_STORAGE_KEY = 'school.calendar';

export const isCalendarPreference = isDisplayCalendar;

export interface FormatInstantOptions {
  locale: string;
  calendar: CalendarPreference;
  /**
   * The IANA zone the time is shown in: the school's, else the person's (D42, `usePlace`). Absent only before the
   * place is known: Intl then uses the phone's zone and the Ethiopian calendar, which needs a zone, is not drawn.
   */
  timeZone?: string | undefined;
}

/**
 * Formats an instant (ISO string or Date) as date + time of day in the given
 * calendar. Gregorian uses Intl; Ethiopian converts the civil date in the time zone
 * via @school/calendar and takes the time of day from Intl. Returns "" for an
 * invalid instant.
 */
export function formatInstant(instant: string | Date, { locale, calendar, timeZone }: FormatInstantOptions): string {
  const date = typeof instant === 'string' ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return '';

  if (calendar === 'gregorian' || !timeZone) {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeStyle: 'short', ...(timeZone ? { timeZone } : {}) }).format(date);
  }

  const day = formatEthiopianDate(instantToEthiopian(date, timeZone), { language: locale, style: 'long' });
  const time = new Intl.DateTimeFormat(locale, { timeStyle: 'short', timeZone }).format(date);
  return `${day}, ${time}`;
}

/** Signed difference between a server timestamp and the device clock midpoint of the request. */
export function computeClockSkewMs(serverTime: string, startedMs: number, finishedMs: number): number {
  return new Date(serverTime).getTime() - (startedMs + finishedMs) / 2;
}

/**
 * Formats a calendar date without a time (`YYYY-MM-DD`, e.g. a date of birth) in the chosen calendar.
 * No time zone is involved: the date is the same everywhere. Returns "" when it is not a date.
 */
export function formatCivilDate(isoDate: string, { locale, calendar }: Pick<FormatInstantOptions, 'locale' | 'calendar'>): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate);
  if (!match) return '';
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return '';
  if (calendar === 'gregorian') {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(date);
  }
  return formatEthiopianDate(gregorianToEthiopian({ year, month, day }), { language: locale, style: 'long' });
}
