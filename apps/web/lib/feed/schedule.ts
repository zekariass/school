/**
 * Scheduled posts are picked and shown in the school's time zone (the `timeZone` of the feed capabilities),
 * never the browser's. A "local" value is `yyyy-MM-ddTHH:mm` as a `datetime-local` input holds it. Pure: the clock
 * and the zone are arguments.
 */

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/;

/** The API refuses a schedule further ahead than this (`feed.schedule_too_far`). */
export const MAX_SCHEDULE_DAYS = 90;
/** A schedule must be at least this far in the future (`feed.schedule_in_past`). */
export const MIN_LEAD_MS = 60_000;

function partsIn(date: Date, timeZone: string): { y: number; mo: number; d: number; h: number; mi: number } | null {
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }).formatToParts(date);
  } catch {
    return null;
  }
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return { y: get("year"), mo: get("month"), d: get("day"), h: get("hour"), mi: get("minute") };
}

/** The zone's offset from UTC at `date`, in milliseconds (seconds are ignored: zones change on whole minutes). */
function offsetMs(date: Date, timeZone: string): number | null {
  const p = partsIn(date, timeZone);
  if (!p) return null;
  const asUtc = Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi);
  return asUtc - Math.floor(date.getTime() / 60_000) * 60_000;
}

/** "yyyy-MM-ddTHH:mm" of `instant` on the wall clock of `timeZone`; null for an unknown zone or instant. */
export function instantToLocal(instant: string | Date, timeZone: string): string | null {
  const date = typeof instant === "string" ? new Date(instant) : instant;
  if (Number.isNaN(date.getTime())) return null;
  const p = partsIn(date, timeZone);
  if (!p) return null;
  const two = (n: number) => String(n).padStart(2, "0");
  return `${String(p.y).padStart(4, "0")}-${two(p.mo)}-${two(p.d)}T${two(p.h)}:${two(p.mi)}`;
}

/** The instant (UTC) at which the wall clock of `timeZone` shows `local`; null when invalid or not existing (a DST gap). */
export function localToInstant(local: string, timeZone: string): Date | null {
  const m = LOCAL.exec(local);
  if (!m) return null;
  const [y, mo, d, h, mi] = m.slice(1).map(Number) as [number, number, number, number, number];
  const naive = Date.UTC(y, mo - 1, d, h, mi);
  if (new Date(naive).toISOString().slice(0, 16) !== local) return null;
  const first = offsetMs(new Date(naive), timeZone);
  if (first === null) return null;
  let result = new Date(naive - first);
  const second = offsetMs(result, timeZone);
  if (second !== null && second !== first) result = new Date(naive - second);
  // The wall clock must read back the same, otherwise that time does not exist in the zone.
  return instantToLocal(result, timeZone) === local ? result : null;
}

export type ScheduleCheck = { ok: true; instant: string } | { ok: false; reason: "invalid" | "past" | "tooFar" };

/** Checks a picked local time against what the API accepts and returns the instant to send (ISO, UTC). */
export function checkSchedule(local: string, timeZone: string, now: Date): ScheduleCheck {
  const instant = localToInstant(local, timeZone);
  if (!instant) return { ok: false, reason: "invalid" };
  if (instant.getTime() < now.getTime() + MIN_LEAD_MS) return { ok: false, reason: "past" };
  if (instant.getTime() > now.getTime() + MAX_SCHEDULE_DAYS * 86_400_000) return { ok: false, reason: "tooFar" };
  return { ok: true, instant: instant.toISOString() };
}

/** The bounds for the `min` and `max` of the date-time input, on the school's wall clock. */
export function scheduleBounds(timeZone: string, now: Date): { min: string; max: string } | null {
  const min = instantToLocal(new Date(now.getTime() + MIN_LEAD_MS), timeZone);
  const max = instantToLocal(new Date(now.getTime() + MAX_SCHEDULE_DAYS * 86_400_000), timeZone);
  return min && max ? { min, max } : null;
}

/** A sensible first value for the picker: tomorrow 08:00 on the school's clock. */
export function defaultScheduleLocal(timeZone: string, now: Date): string {
  const tomorrow = instantToLocal(new Date(now.getTime() + 86_400_000), timeZone);
  return tomorrow ? `${tomorrow.slice(0, 10)}T08:00` : "";
}
