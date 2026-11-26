import { instantToLocal, localToInstant } from "@/lib/feed/schedule";

/**
 * A scheduled message is picked and shown in the school's time zone (`timeZone` of the messaging capabilities), never the
 * browser's. Wall-clock arithmetic is the feed's (`lib/feed/schedule`); only the limits differ: the API accepts 1 minute to
 * 90 days ahead (`messaging.schedule_invalid`), so the client keeps a little inside both edges to absorb clock drift.
 */
export { instantToLocal, localToInstant };

export const MIN_LEAD_MS = 90_000;
export const MAX_AHEAD_MS = 90 * 86_400_000 - 3_600_000;

export type ScheduleCheck = { ok: true; instant: string } | { ok: false; reason: "invalid" | "past" | "tooFar" };

export function checkSchedule(local: string, timeZone: string, now: Date): ScheduleCheck {
  const instant = localToInstant(local, timeZone);
  if (!instant) return { ok: false, reason: "invalid" };
  if (instant.getTime() < now.getTime() + MIN_LEAD_MS) return { ok: false, reason: "past" };
  if (instant.getTime() > now.getTime() + MAX_AHEAD_MS) return { ok: false, reason: "tooFar" };
  return { ok: true, instant: instant.toISOString() };
}

/** The `min` and `max` of the date-time input, on the school's wall clock. */
export function scheduleBounds(timeZone: string, now: Date): { min: string; max: string } | null {
  const min = instantToLocal(new Date(now.getTime() + MIN_LEAD_MS), timeZone);
  const max = instantToLocal(new Date(now.getTime() + MAX_AHEAD_MS), timeZone);
  return min && max ? { min, max } : null;
}

/** A sensible first value: tomorrow 08:00 on the school's clock. */
export function defaultScheduleLocal(timeZone: string, now: Date): string {
  const tomorrow = instantToLocal(new Date(now.getTime() + 86_400_000), timeZone);
  return tomorrow ? `${tomorrow.slice(0, 10)}T08:00` : "";
}
