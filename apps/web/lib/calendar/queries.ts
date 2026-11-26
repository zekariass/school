import type { components } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ensureOk, unwrap } from "@/lib/api-error";
import type { FetchWindow } from "./grid";
import type { CalendarItem } from "./items";
import type { CreateBody, OccurrenceBody, UpdateBody } from "./form";
import type { RsvpAnswer } from "./rsvp";

type S = components["schemas"];

export type CalendarEvent = S["CalendarEventView"];
export type CalendarCapabilities = S["CalendarCapabilities"];

const api = () => getBrowserApi();

/**
 * Query keys. Event titles, places, descriptions and (for parent meetings) children and answers live in this memory-only
 * cache: never in a URL (ids only) or in storage. The cache is dropped soon after a screen is left (`CALENDAR_GC_MS`).
 */
export const ck = {
  all: ["calendar"] as const,
  /** Every window of every scope (a prefix: the lists, not the single events). */
  windows: ["calendar", "window"] as const,
  window: (schoolId: string | null, studentId: string | null, sources: string, from: string, to: string) =>
    ["calendar", "window", schoolId ?? "all", studentId ?? "all", sources, from, to] as const,
  event: (eventId: string) => ["calendar", "event", eventId] as const,
  capabilities: (schoolId: string) => ["calendar", "capabilities", schoolId] as const,
};

/** How long unused calendar data stays in memory: a short while, so a closed tab or a left screen keeps nothing for long. */
export const CALENDAR_GC_MS = 120_000;

/** One call returns at most this many items; a window with more says `truncated` or gives a cursor. */
export const PAGE_LIMIT = 500;
/** Pages followed per window (the server caps a window at 2000 items per source anyway). */
const MAX_PAGES = 4;

export interface WindowData {
  items: CalendarItem[];
  /** True when the window held more than was returned (narrow the window). */
  truncated: boolean;
}

export interface WindowScope {
  schoolId: string | null;
  studentId: string | null;
  sources: string;
}

/** The items of a window, following the cursor for a few pages. */
export async function fetchCalendarWindow(scope: WindowScope, window: FetchWindow): Promise<WindowData> {
  const items: CalendarItem[] = [];
  let cursor: string | undefined;
  let truncated = false;
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = unwrap(
      await api().GET("/api/v1/calendar", {
        params: {
          query: {
            from: window.from,
            to: window.to,
            schoolId: scope.schoolId ?? undefined,
            studentId: scope.studentId ?? undefined,
            sources: scope.sources,
            cursor,
            limit: PAGE_LIMIT,
          },
        },
      }),
    );
    items.push(...result.items);
    truncated = truncated || !!result.truncated;
    cursor = result.nextCursor;
    if (!cursor) break;
  }
  return { items, truncated: truncated || !!cursor };
}

export const fetchEvent = async (eventId: string) =>
  unwrap(await api().GET("/api/v1/events/{eventId}", { params: { path: { eventId } } }));

export const fetchCalendarCapabilities = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/events/capabilities", { params: { path: { schoolId } } }));

export const createSchoolEvent = async (schoolId: string, body: CreateBody, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/events", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const createPersonalEvent = async (body: CreateBody, key: string) =>
  unwrap(await api().POST("/api/v1/me/events", { headers: { "Idempotency-Key": key }, body }));

export const updateEvent = async (eventId: string, body: UpdateBody) =>
  unwrap(await api().PATCH("/api/v1/events/{eventId}", { params: { path: { eventId } }, body }));

export const cancelEvent = async (eventId: string, reason: string) =>
  unwrap(await api().POST("/api/v1/events/{eventId}/cancel", { params: { path: { eventId } }, body: { reason: reason.trim() || undefined } }));

export const deleteEvent = async (eventId: string) => ensureOk(await api().DELETE("/api/v1/events/{eventId}", { params: { path: { eventId } } }));

export const answerEvent = async (eventId: string, status: RsvpAnswer) =>
  unwrap(await api().PUT("/api/v1/events/{eventId}/rsvp", { params: { path: { eventId } }, body: { status } }));

export const cancelOccurrence = async (eventId: string, occurrenceId: string, reason: string) =>
  unwrap(
    await api().POST("/api/v1/events/{eventId}/occurrences/{occurrenceId}/cancel", {
      params: { path: { eventId, occurrenceId } },
      body: { reason: reason.trim() || undefined },
    }),
  );

export const updateOccurrence = async (eventId: string, occurrenceId: string, body: OccurrenceBody) =>
  unwrap(await api().PATCH("/api/v1/events/{eventId}/occurrences/{occurrenceId}", { params: { path: { eventId, occurrenceId } }, body }));

export const restoreOccurrence = async (eventId: string, occurrenceId: string) =>
  unwrap(await api().DELETE("/api/v1/events/{eventId}/occurrences/{occurrenceId}", { params: { path: { eventId, occurrenceId } } }));
