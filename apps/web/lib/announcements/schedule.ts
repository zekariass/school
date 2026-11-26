import { instantToLocal, localToInstant } from "@/lib/feed/schedule";

/**
 * A scheduled announcement is picked and shown in the school's time zone (`timeZone` of the capabilities), never the
 * browser's. The wall-clock arithmetic is the feed's (`lib/feed/schedule`); only the limits differ.
 */
export { instantToLocal, localToInstant };

/** The API refuses a schedule further ahead than this (`announcement.schedule_too_far`). */
export const MAX_SCHEDULE_DAYS = 180;
/** A schedule must be in the future (`announcement.schedule_in_past`); a minute of lead absorbs clock drift. */
export const MIN_LEAD_MS = 60_000;

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
