import { ethiopianMonthName, formatEthiopianDate } from "@school/calendar";
import { parseIso, toDisplay, type CalendarSystem, type Weekday } from "./dates";

/**
 * Labels of the calendar views. Every instant is formatted in an explicit time zone and every date as a date (no zone):
 * nothing reads the browser's. Ethiopian names come from `@school/calendar`; weekday names are Intl's in both calendars
 * (the days of the week are the same).
 */

function utcDate(iso: string): Date {
  const d = parseIso(iso);
  if (!d) throw new RangeError(`Invalid date: ${iso}`);
  return new Date(Date.UTC(d.year, d.month - 1, d.day));
}

/** "December 2026" or "Tahsas 2019" for a month of the display calendar. */
export function monthTitle(year: number, month: number, system: CalendarSystem, locale: string): string {
  if (system === "ethiopian") return `${ethiopianMonthName(month, locale)} ${year}`;
  return new Intl.DateTimeFormat(locale, { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(year, month - 1, 1)));
}

/** The weekday name of a weekday number (0 = Sunday), short or long. */
export function weekdayName(weekday: Weekday, locale: string, style: "short" | "long" | "narrow" = "short"): string {
  // 2024-01-07 was a Sunday.
  return new Intl.DateTimeFormat(locale, { weekday: style, timeZone: "UTC" }).format(new Date(Date.UTC(2024, 0, 7 + weekday)));
}

/** "Monday, December 7, 2026" or "Monday, Tahsas 28, 2019 E.C." in the display calendar. */
export function dayLabel(iso: string, system: CalendarSystem, locale: string): string {
  const weekday = new Intl.DateTimeFormat(locale, { weekday: "long", timeZone: "UTC" }).format(utcDate(iso));
  if (system === "ethiopian") return `${weekday}, ${formatEthiopianDate(toDisplay(iso, system), { language: locale, style: "long" })}`;
  return new Intl.DateTimeFormat(locale, { dateStyle: "full", timeZone: "UTC" }).format(utcDate(iso));
}

/** A short date ("Dec 7" / "Tahsas 28") for ranges. */
export function shortDay(iso: string, system: CalendarSystem, locale: string): string {
  if (system === "ethiopian") {
    const d = toDisplay(iso, system);
    return `${ethiopianMonthName(d.month, locale)} ${d.day}`;
  }
  return new Intl.DateTimeFormat(locale, { month: "short", day: "numeric", timeZone: "UTC" }).format(utcDate(iso));
}

/** "Dec 7 - Dec 13" style range of a week. */
export function rangeLabel(from: string, to: string, system: CalendarSystem, locale: string): string {
  return `${shortDay(from, system, locale)} – ${shortDay(to, system, locale)}`;
}

/** The time of day of an instant on the wall clock of `timeZone` ("9:00 AM"). */
export function timeOfDay(instant: string | Date, timeZone: string, locale: string): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(locale, { timeStyle: "short", timeZone }).format(date);
}

/** "9:00 AM – 10:00 AM" in the event's zone. */
export function timeRange(startAt: string, endAt: string, timeZone: string, locale: string): string {
  const start = timeOfDay(startAt, timeZone, locale);
  const end = timeOfDay(endAt, timeZone, locale);
  return start === end || new Date(endAt).getTime() <= new Date(startAt).getTime() ? start : `${start} – ${end}`;
}

/** The zone shown in a note: "Africa/Addis_Ababa" as a short, readable city name where possible. */
export function zoneLabel(timeZone: string, instant: string | Date, locale: string): string {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  try {
    const part = new Intl.DateTimeFormat(locale, { timeZone, timeZoneName: "short" }).formatToParts(date).find((p) => p.type === "timeZoneName");
    return part ? `${timeZone} (${part.value})` : timeZone;
  } catch {
    return timeZone;
  }
}
