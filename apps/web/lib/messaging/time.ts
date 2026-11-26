import { formatEthiopianDate, instantToEthiopian } from "@school/calendar";
import { DISPLAY_TIME_ZONE, formatInstant, type CalendarPreference } from "@/lib/format-instant";

export interface TimeOptions {
  locale: string;
  calendar: CalendarPreference;
  /** The school's time zone (capabilities.timeZone); never the browser's. */
  timeZone?: string;
}

/** "10:42" of an instant on the school's clock. */
export function formatClock(instant: string | Date, { locale, timeZone = DISPLAY_TIME_ZONE }: TimeOptions): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return "";
  try {
    return new Intl.DateTimeFormat(locale, { timeStyle: "short", timeZone }).format(date);
  } catch {
    return "";
  }
}

/** "26 November 2026" (or the Ethiopian date) of an instant on the school's clock. */
export function formatDay(instant: string | Date, { locale, calendar, timeZone = DISPLAY_TIME_ZONE }: TimeOptions): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return "";
  try {
    if (calendar === "ethiopian") return formatEthiopianDate(instantToEthiopian(date, timeZone), { language: locale, style: "long" });
    return new Intl.DateTimeFormat(locale, { dateStyle: "long", timeZone }).format(date);
  } catch {
    return "";
  }
}

/** Date and time, as a tooltip and for scheduled messages. */
export function formatFull(instant: string | Date, options: TimeOptions): string {
  return formatInstant(instant, { locale: options.locale, calendar: options.calendar, timeZone: options.timeZone });
}
