import { formatEthiopianDate, gregorianToEthiopian } from "@school/calendar";
import type { CalendarPreference } from "@/lib/format-instant";

/** Parses "yyyy-mm-dd" (a calendar date, no time zone) into its parts, or null. */
export function parseIsoDate(value: string | null | undefined): { year: number; month: number; day: number } | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value ?? "");
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  return month >= 1 && month <= 12 && day >= 1 && day <= 31 ? { year, month, day } : null;
}

/** A calendar date ("2026-09-01") in the viewer's calendar and language. Dates have no time zone, so none is applied. */
export function formatCivilDate(
  value: string | null | undefined,
  { locale, calendar }: { locale: string; calendar: CalendarPreference },
): string {
  const parts = parseIsoDate(value);
  if (!parts) return value ?? "";
  if (calendar === "ethiopian") {
    return formatEthiopianDate(gregorianToEthiopian(parts), { language: locale, style: "medium" });
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(
    new Date(Date.UTC(parts.year, parts.month - 1, parts.day)),
  );
}
