import { addDays, localDateIn } from '@/lib/attendance/dates';
import { isCivilDate } from '@/lib/sync/operations';

/**
 * Scheduled posts are read in the school's time zone (`FeedCapabilities.timeZone`), never the phone's: a teacher on
 * holiday still schedules "Monday 07:30" in school time. A pick is a school-local calendar date plus minutes after
 * midnight; only `toInstant` turns it into the UTC instant the API stores.
 */

/** The API refuses a schedule more than 90 days ahead or in the past. */
export const MAX_SCHEDULE_DAYS = 90;
export const MIN_LEAD_MS = 60_000;
export const MINUTE_STEP = 5;

const FALLBACK_ZONE = 'UTC';
const DAY_MS = 86_400_000;

export interface SchedulePick {
  /** `YYYY-MM-DD` in the school's zone. */
  date: string;
  /** Minutes after local midnight, 0..1439. */
  minutes: number;
}

/** The zone the device knows: the school's, or UTC when this phone's runtime has never heard of it. */
export function usableZone(timeZone: string | null | undefined): string {
  if (timeZone) {
    try {
      new Intl.DateTimeFormat('en-US', { timeZone });
      return timeZone;
    } catch {
      // fall through
    }
  }
  return FALLBACK_ZONE;
}

interface Parts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
}

function partsIn(ms: number, timeZone: string): Parts {
  const formatted = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(new Date(ms));
  const get = (type: string) => Number(formatted.find((part) => part.type === type)?.value ?? 0);
  return { year: get('year'), month: get('month'), day: get('day'), hour: get('hour') % 24, minute: get('minute') };
}

/** Milliseconds the zone is ahead of UTC at that instant. */
function offsetAt(ms: number, timeZone: string): number {
  const p = partsIn(ms, timeZone);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute) - Math.floor(ms / 60_000) * 60_000;
}

/**
 * The instant of a school-local date and time. When clocks go back the time happens twice and the first one is taken;
 * a time that does not exist (spring forward) moves onward, as the clocks do.
 */
export function toInstant(pick: SchedulePick, timeZone: string): Date {
  const zone = usableZone(timeZone);
  const [year, month, day] = pick.date.split('-').map(Number) as [number, number, number];
  const wall = Date.UTC(year, month - 1, day, Math.floor(pick.minutes / 60), pick.minutes % 60);
  // The zone's offset a day before and a day after: at most one transition lies between them.
  const before = offsetAt(wall - DAY_MS, zone);
  const after = offsetAt(wall + DAY_MS, zone);
  const valid = [...new Set([wall - before, wall - after])].filter((instant) => offsetAt(instant, zone) === wall - instant);
  return new Date(valid.length > 0 ? Math.min(...valid) : wall - before);
}

/** The school-local date and time of an instant. */
export function fromInstant(instant: Date | string | number, timeZone: string): SchedulePick {
  const zone = usableZone(timeZone);
  const ms = new Date(instant).getTime();
  const p = partsIn(ms, zone);
  return { date: `${String(p.year).padStart(4, '0')}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`, minutes: p.hour * 60 + p.minute };
}

/** A sensible first choice: the next whole half hour at least an hour away, in school time. */
export function defaultPick(nowMs: number, timeZone: string): SchedulePick {
  const zone = usableZone(timeZone);
  const start = new Date(nowMs + 60 * 60_000);
  const local = fromInstant(start, zone);
  const rounded = Math.ceil(local.minutes / 30) * 30;
  return rounded >= 1440 ? { date: addDays(local.date, 1), minutes: 0 } : { date: local.date, minutes: rounded };
}

export type ScheduleProblem = 'past' | 'too_far' | 'invalid';

/** Why a pick cannot be sent (the API still decides), or null. */
export function scheduleProblem(pick: SchedulePick, timeZone: string, nowMs: number): ScheduleProblem | null {
  if (!isCivilDate(pick.date) || !(pick.minutes >= 0 && pick.minutes < 1440)) return 'invalid';
  const at = toInstant(pick, timeZone).getTime();
  if (at < nowMs + MIN_LEAD_MS) return 'past';
  if (at > nowMs + MAX_SCHEDULE_DAYS * 86_400_000) return 'too_far';
  return null;
}

/** Whether the date can go one day earlier / later without leaving the allowed window (school "today" to +90 days). */
export function canStepDate(pick: SchedulePick, direction: -1 | 1, timeZone: string, nowMs: number): boolean {
  const today = localDateIn(usableZone(timeZone), nowMs);
  const next = addDays(pick.date, direction);
  const last = addDays(today, MAX_SCHEDULE_DAYS);
  return next >= today && next <= last;
}

export function stepDate(pick: SchedulePick, direction: -1 | 1): SchedulePick {
  return { ...pick, date: addDays(pick.date, direction) };
}

/** Moves the time by whole steps, wrapping around midnight into the neighbouring day. */
export function stepMinutes(pick: SchedulePick, direction: -1 | 1, step: number = MINUTE_STEP * 3): SchedulePick {
  const total = pick.minutes + direction * step;
  if (total < 0) return { date: addDays(pick.date, -1), minutes: total + 1440 };
  if (total >= 1440) return { date: addDays(pick.date, 1), minutes: total - 1440 };
  return { ...pick, minutes: total };
}

/** `07:30` for a pick (24-hour: the same digits whatever the phone's settings). */
export function clockText(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  return `${String(hours).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}
