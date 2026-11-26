import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';

import { fetchWindow } from './window';
import { MAX_PAGES, PAGE_LIMIT, sourcesParam, type CalendarFilter } from './keys';
import type { DateRange } from './grid';
import type {
  CalendarCapabilities,
  CalendarEventRequest,
  CalendarEventUpdateRequest,
  CalendarEventView,
  CalendarItem,
  CalendarOccurrenceUpdateRequest,
  RsvpAnswer,
} from './types';

/**
 * One call per endpoint, typed from the generated client. Only the two creates carry an `Idempotency-Key` (a retry of the same
 * submit returns the first event instead of a second one); everything else is idempotent by itself. Nothing here is persisted.
 */

export interface CalendarWindow {
  items: CalendarItem[];
  /** The API says the window held more than it returns: narrow it (a shorter range). */
  truncated: boolean;
  /** More pages exist than this app follows. */
  partial: boolean;
}

/** The union for a range of days: the padded window, every page (at most `MAX_PAGES`). */
export async function fetchCalendarRange(filter: CalendarFilter, range: DateRange): Promise<CalendarWindow> {
  const window = fetchWindow(range);
  if (!window) return { items: [], truncated: false, partial: false };
  const items: CalendarItem[] = [];
  let truncated = false;
  let cursor: string | undefined;
  for (let page = 0; page < MAX_PAGES; page++) {
    const data = await request(async () =>
      unwrap(
        await api.GET('/api/v1/calendar', {
          params: {
            query: {
              from: window.from,
              to: window.to,
              limit: PAGE_LIMIT,
              sources: sourcesParam(filter.sources),
              ...(filter.schoolId ? { schoolId: filter.schoolId } : {}),
              ...(filter.studentId ? { studentId: filter.studentId } : {}),
              ...(cursor ? { cursor } : {}),
            },
          },
        }),
      ),
    );
    items.push(...data.items);
    truncated = truncated || !!data.truncated;
    if (!data.nextCursor) return { items, truncated, partial: false };
    cursor = data.nextCursor;
  }
  return { items, truncated, partial: true };
}

export const fetchEvent = (eventId: string): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.GET('/api/v1/events/{eventId}', { params: { path: { eventId } } })));

export const fetchCapabilities = (schoolId: string): Promise<CalendarCapabilities> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/events/capabilities', { params: { path: { schoolId } } })));

export const createSchoolEvent = (schoolId: string, body: CalendarEventRequest, idempotencyKey: string): Promise<CalendarEventView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/events', { params: { path: { schoolId } }, body, headers: { 'Idempotency-Key': idempotencyKey } })),
  );

export const createPersonalEvent = (body: CalendarEventRequest, idempotencyKey: string): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.POST('/api/v1/me/events', { body, headers: { 'Idempotency-Key': idempotencyKey } })));

export const updateEvent = (eventId: string, body: CalendarEventUpdateRequest): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.PATCH('/api/v1/events/{eventId}', { params: { path: { eventId } }, body })));

export const cancelEvent = (eventId: string, reason: string | null): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.POST('/api/v1/events/{eventId}/cancel', { params: { path: { eventId } }, body: reason ? { reason } : {} })));

export const deleteEvent = (eventId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/events/{eventId}', { params: { path: { eventId } } })));

export const answerEvent = (eventId: string, status: RsvpAnswer): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.PUT('/api/v1/events/{eventId}/rsvp', { params: { path: { eventId } }, body: { status } })));

const occurrencePath = (eventId: string, occurrenceId: string) => ({ params: { path: { eventId, occurrenceId } } });

export const cancelOccurrence = (eventId: string, occurrenceId: string, reason: string | null): Promise<CalendarEventView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/events/{eventId}/occurrences/{occurrenceId}/cancel', { ...occurrencePath(eventId, occurrenceId), body: reason ? { reason } : {} })),
  );

export const updateOccurrence = (eventId: string, occurrenceId: string, body: CalendarOccurrenceUpdateRequest): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.PATCH('/api/v1/events/{eventId}/occurrences/{occurrenceId}', { ...occurrencePath(eventId, occurrenceId), body })));

export const restoreOccurrence = (eventId: string, occurrenceId: string): Promise<CalendarEventView> =>
  request(async () => unwrap(await api.DELETE('/api/v1/events/{eventId}/occurrences/{occurrenceId}', occurrencePath(eventId, occurrenceId))));
