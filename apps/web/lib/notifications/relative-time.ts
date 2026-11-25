const UNITS: readonly [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 3600],
  ["month", 30 * 24 * 3600],
  ["week", 7 * 24 * 3600],
  ["day", 24 * 3600],
  ["hour", 3600],
  ["minute", 60],
];

/** "5 minutes ago", "yesterday", in the UI language; under a minute reads as "now". */
export function formatRelativeTime(instant: string | Date, now: Date, locale: string): string {
  const then = typeof instant === "string" ? new Date(instant) : instant;
  const seconds = Math.round((then.getTime() - now.getTime()) / 1000);
  const formatter = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const abs = Math.abs(seconds);
  for (const [unit, size] of UNITS) {
    if (abs >= size) return formatter.format(Math.trunc(seconds / size), unit);
  }
  return formatter.format(0, "second");
}
