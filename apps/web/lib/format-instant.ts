import { formatEthiopianDate, instantToEthiopian } from "@school/calendar";
import { isDisplayCalendar, type DisplayCalendar } from "@school/validation/country";

/** The calendars the app can draw (a country offers some of them, see `offeredCalendars`). */
export type CalendarPreference = DisplayCalendar;

export const isCalendarPreference = isDisplayCalendar;

export interface FormatInstantOptions {
  locale: string;
  calendar: CalendarPreference;
  /**
   * The IANA zone to show the time in: the school's in a workspace, else the person's (D42, see `useDisplayPlace`).
   * Absent only before the place is known (a public page): then Intl uses the browser's zone and the Ethiopian
   * calendar, which needs a zone, is not drawn.
   */
  timeZone?: string;
}

/**
 * Formats an instant (ISO string or Date) as date + time of day in the given
 * calendar. Gregorian uses Intl; Ethiopian converts the civil date in the
 * timezone via @school/calendar and takes the time of day from Intl.
 */
export function formatInstant(instant: string | Date, { locale, calendar, timeZone }: FormatInstantOptions): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return "";

  if (calendar === "gregorian" || !timeZone) {
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "long",
      timeStyle: "short",
      timeZone,
    }).format(date);
  }

  const day = formatEthiopianDate(instantToEthiopian(date, timeZone), {
    language: locale,
    style: "long",
  });
  const time = new Intl.DateTimeFormat(locale, { timeStyle: "short", timeZone }).format(date);
  return `${day}, ${time}`;
}
