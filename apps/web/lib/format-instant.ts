import { formatEthiopianDate, instantToEthiopian } from "@school/calendar";

export type CalendarPreference = "gregorian" | "ethiopian";

export const CALENDAR_PREFERENCES: readonly CalendarPreference[] = ["gregorian", "ethiopian"];

/** Placeholder until the user's / school's timezone comes from settings. */
export const DISPLAY_TIME_ZONE = "Africa/Addis_Ababa";

export function isCalendarPreference(value: unknown): value is CalendarPreference {
  return value === "gregorian" || value === "ethiopian";
}

export function defaultCalendarFor(locale: string): CalendarPreference {
  return locale === "am" ? "ethiopian" : "gregorian";
}

export interface FormatInstantOptions {
  locale: string;
  calendar: CalendarPreference;
  timeZone?: string;
}

/**
 * Formats an instant (ISO string or Date) as date + time of day in the given
 * calendar. Gregorian uses Intl; Ethiopian converts the civil date in the
 * timezone via @school/calendar and takes the time of day from Intl.
 */
export function formatInstant(
  instant: string | Date,
  { locale, calendar, timeZone = DISPLAY_TIME_ZONE }: FormatInstantOptions,
): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return "";

  if (calendar === "gregorian") {
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
