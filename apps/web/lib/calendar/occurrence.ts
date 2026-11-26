import { isUuid } from "@/lib/feed/ids";
import { addDays, diffDays, isoInZone } from "./dates";
import type { OccurrenceValues } from "./form";
import type { EventView } from "./rsvp";

/**
 * Items of a recurring event have the id "eventId@occurrenceId"; the occurrence id is the UTC instant the occurrence has
 * in the series (`20261207T060000Z`) and stays the same when the occurrence is moved. Ids go into API paths: only
 * well-formed ones pass.
 */

const OCCURRENCE = /^\d{8}T\d{6}Z$/;

export function isOccurrenceId(value: unknown): value is string {
  return typeof value === "string" && OCCURRENCE.test(value) && occurrenceInstant(value) !== null;
}

/** The instant an occurrence id names, or null. */
export function occurrenceInstant(id: string): Date | null {
  const m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(id);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map(Number) as [number, number, number, number, number, number];
  const date = new Date(Date.UTC(y, mo - 1, d, h, mi, s));
  // Rejects dates that roll over (month 13, day 31 of a short month, ...).
  return date.getUTCFullYear() === y && date.getUTCMonth() === mo - 1 && date.getUTCDate() === d ? date : null;
}

/** The occurrence id of an instant: "20261207T060000Z". */
export function occurrenceIdOf(instant: Date): string {
  return instant.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

export interface ItemRef {
  eventId: string;
  /** Set for an occurrence of a recurring event. */
  occurrenceId: string | null;
}

/** Splits an item id into the event and its occurrence; null for anything that is not an event item id. */
export function parseItemId(id: string): ItemRef | null {
  const at = id.indexOf("@");
  const eventId = at < 0 ? id : id.slice(0, at);
  if (!isUuid(eventId)) return null;
  if (at < 0) return { eventId, occurrenceId: null };
  const occurrenceId = id.slice(at + 1);
  return isOccurrenceId(occurrenceId) ? { eventId, occurrenceId } : null;
}

/** The reference of a calendar item that belongs to an event (its own fields win over the id's parts). */
export function itemRef(item: { id: string; eventId?: string; occurrenceId?: string }): ItemRef | null {
  if (item.eventId && isUuid(item.eventId)) {
    const occurrenceId = item.occurrenceId ?? null;
    if (occurrenceId !== null && !isOccurrenceId(occurrenceId)) return null;
    return { eventId: item.eventId, occurrenceId };
  }
  return parseItemId(item.id);
}

/** What one occurrence of a series looks like now: the series' values with its exception (cancelled or changed) applied. */
export interface OccurrenceState extends OccurrenceValues {
  status: "SCHEDULED" | "CANCELLED";
  /** True when the occurrence was changed (moved or edited) from what the series says. */
  modified: boolean;
  /** The reason of a cancelled occurrence, when one was given. */
  reason: string | null;
}

/**
 * The occurrence `occurrenceId` of a series, from the event view alone: its own place in the series (the id is that
 * instant; wall-clock times survive daylight saving, the length is the series') unless an exception moved it. Null for an
 * id that is not one.
 */
export function occurrenceState(event: EventView, occurrenceId: string): OccurrenceState | null {
  const origin = occurrenceInstant(occurrenceId);
  if (!origin) return null;
  const override = event.overrides?.find((o) => o.occurrenceId === occurrenceId);
  const zone = event.timeZone;
  const allDay = !!event.allDay;
  let startAt: string;
  let endAt: string;
  let startDate: string | undefined;
  let endDate: string | undefined;
  if (allDay) {
    const length = event.startDate && event.endDate ? diffDays(event.startDate, event.endDate) : 0;
    startDate = override?.startDate ?? isoInZone(origin, zone);
    endDate = override?.endDate ?? addDays(startDate, length);
    startAt = override?.startAt ?? origin.toISOString();
    endAt = override?.endAt ?? origin.toISOString();
  } else {
    const length = event.startAt && event.endAt ? new Date(event.endAt).getTime() - new Date(event.startAt).getTime() : 0;
    startAt = override?.startAt ?? origin.toISOString();
    endAt = override?.endAt ?? new Date(new Date(startAt).getTime() + length).toISOString();
  }
  return {
    title: override?.title || event.title,
    description: override?.description || event.description || undefined,
    location: override?.location || event.location || undefined,
    allDay,
    startAt,
    endAt,
    startDate,
    endDate,
    timeZone: zone,
    status: override?.status === "CANCELLED" ? "CANCELLED" : "SCHEDULED",
    modified: override?.status === "MODIFIED",
    reason: override?.status === "CANCELLED" ? (override.reason ?? null) : null,
  };
}
