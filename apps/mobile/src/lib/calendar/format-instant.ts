import { formatEthiopianDate, instantToEthiopian } from '@school/calendar';

import { DISPLAY_TIME_ZONE } from '@/lib/i18n/locale';

export type CalendarPreference = 'gregorian' | 'ethiopian';

export const CALENDAR_PREFERENCES: readonly CalendarPreference[] = ['gregorian', 'ethiopian'];

/** kv-store key of the saved calendar display preference. */
export const CALENDAR_STORAGE_KEY = 'school.calendar';

export function isCalendarPreference(value: unknown): value is CalendarPreference {
  return value === 'gregorian' || value === 'ethiopian';
}

/** Ethiopian by default for Amharic, Gregorian otherwise (same rule as the web app). */
export function defaultCalendarFor(locale: string): CalendarPreference {
  return locale === 'am' ? 'ethiopian' : 'gregorian';
}

export interface FormatInstantOptions {
  locale: string;
  calendar: CalendarPreference;
  timeZone?: string;
}

/**
 * Formats an instant (ISO string or Date) as date + time of day in the given
 * calendar. Gregorian uses Intl; Ethiopian converts the civil date in the time zone
 * via @school/calendar and takes the time of day from Intl. Returns "" for an
 * invalid instant.
 */
export function formatInstant(
  instant: string | Date,
  { locale, calendar, timeZone = DISPLAY_TIME_ZONE }: FormatInstantOptions,
): string {
  const date = typeof instant === 'string' ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return '';

  if (calendar === 'gregorian') {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeStyle: 'short', timeZone }).format(date);
  }

  const day = formatEthiopianDate(instantToEthiopian(date, timeZone), { language: locale, style: 'long' });
  const time = new Intl.DateTimeFormat(locale, { timeStyle: 'short', timeZone }).format(date);
  return `${day}, ${time}`;
}

/** Signed difference between a server timestamp and the device clock midpoint of the request. */
export function computeClockSkewMs(serverTime: string, startedMs: number, finishedMs: number): number {
  return new Date(serverTime).getTime() - (startedMs + finishedMs) / 2;
}
