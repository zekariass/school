import { addDays, daysBetween, type IsoDate } from './civil';
import type { CalendarEventView, CalendarOverride } from './types';
import { dateIn } from './zone';

/**
 * Occurrences of repeating events. The union names one `eventId@occurrenceId` where the occurrence id is the UTC instant the
 * occurrence has in the series (`20261207T060000Z`): it stays the same when the occurrence is moved or changed, and it is the
 * path part of the occurrence endpoints.
 */

const OCCURRENCE_ID = /^\d{8}T\d{6}Z$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export function isOccurrenceId(value: unknown): value is string {
  return typeof value === 'string' && OCCURRENCE_ID.test(value) && occurrenceInstant(value) !== null;
}

/** The instant (ms) an occurrence id names; null when it is not an id. */
export function occurrenceInstant(id: string): number | null {
  const match = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(id);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1).map(Number) as [number, number, number, number, number, number];
  const ms = Date.UTC(year, month - 1, day, hour, minute, second);
  const check = new Date(ms);
  return check.getUTCFullYear() === year && check.getUTCMonth() === month - 1 && check.getUTCDate() === day ? ms : null;
}

/** `20261207T060000Z` for an instant: how the API names the occurrence that starts then. */
export function occurrenceIdOf(ms: number): string {
  return new Date(ms).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');
}

export interface SplitItemId {
  eventId: string;
  occurrenceId: string | null;
}

/** The event and (for a repeating one) the occurrence of an item id; null for ids of holidays and academic dates. */
export function splitItemId(id: string): SplitItemId | null {
  const [eventId, occurrenceId, ...rest] = id.split('@');
  if (!eventId || !UUID.test(eventId) || rest.length > 0) return null;
  if (occurrenceId === undefined) return { eventId, occurrenceId: null };
  return isOccurrenceId(occurrenceId) ? { eventId, occurrenceId } : null;
}

export interface OccurrenceTimes {
  allDay: boolean;
  /** Timed: instants (ms) of the start and end. */
  startMs: number | null;
  endMs: number | null;
  /** All-day: inclusive dates. */
  startDate: IsoDate | null;
  endDate: IsoDate | null;
  /** The occurrence is cancelled (an exception), with the reason when one was given. */
  cancelled: boolean;
  cancellationReason: string | null;
  /** It differs from the series (moved or changed). */
  modified: boolean;
  /** Changed title, text or place of this occurrence only. */
  override: CalendarOverride | null;
}

/**
 * When one occurrence of a series happens, from the event as the API returns it: the series' own times shifted to the
 * occurrence, unless an exception moved it. Timed occurrences keep the event's exact length; all-day ones its number of days.
 */
export function occurrenceTimes(event: CalendarEventView, occurrenceId: string | null): OccurrenceTimes {
  const override = occurrenceId ? (event.overrides ?? []).find((item) => item.occurrenceId === occurrenceId) ?? null : null;
  const base: OccurrenceTimes = {
    allDay: !!event.allDay,
    startMs: event.startAt ? Date.parse(event.startAt) : null,
    endMs: event.endAt ? Date.parse(event.endAt) : null,
    startDate: event.startDate ?? null,
    endDate: event.endDate ?? null,
    cancelled: event.status === 'CANCELLED',
    cancellationReason: event.cancellationReason ?? null,
    modified: false,
    override,
  };
  const at = occurrenceId ? occurrenceInstant(occurrenceId) : null;
  if (at === null || !event.recurring) return base;

  let result: OccurrenceTimes;
  if (event.allDay && event.startDate && event.endDate) {
    const first = dateIn(at, event.timeZone);
    result = { ...base, startMs: at, endMs: null, startDate: first, endDate: addDays(first, daysBetween(event.startDate, event.endDate)) };
  } else if (base.startMs !== null && base.endMs !== null) {
    result = { ...base, startMs: at, endMs: at + (base.endMs - base.startMs) };
  } else {
    result = base;
  }
  if (!override) return result;
  if (override.status === 'CANCELLED') {
    return { ...result, cancelled: true, cancellationReason: override.reason ?? null };
  }
  const moved: OccurrenceTimes = { ...result, modified: true };
  if (override.startAt && override.endAt) {
    moved.startMs = Date.parse(override.startAt);
    moved.endMs = Date.parse(override.endAt);
  }
  if (override.startDate && override.endDate) {
    moved.startDate = override.startDate;
    moved.endDate = override.endDate;
  }
  return moved;
}

/** The text of one occurrence: the exception's own title, text and place where it has them. */
export function occurrenceText(event: CalendarEventView, override: CalendarOverride | null): { title: string; description: string | null; location: string | null } {
  return {
    title: override?.title || event.title,
    description: override?.description || event.description || null,
    location: override?.location || event.location || null,
  };
}
