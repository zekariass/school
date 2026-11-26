import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';

import { fetchClasses } from '@/lib/announcements/requests';
import { useMe } from '@/lib/auth/use-me';
import { useSession } from '@/lib/session/session-provider';

import type { DateRange } from './grid';
import {
  CALENDAR_GC_MS,
  CALENDAR_ROOT,
  CALENDAR_STALE_MS,
  capabilitiesKey,
  classChoicesKey,
  eventKey,
  itemsKey,
  sameFilter,
  type CalendarFilter,
} from './keys';
import { fetchCalendarRange, fetchCapabilities, fetchEvent } from './requests';
import type { CalendarEventView } from './types';

/**
 * Everything here is memory-only: the keys sit under `calendar`, which `isPersistableCalendarQuery` keeps out of the persisted
 * cache, and a page that nobody uses is dropped after five minutes. There is no offline calendar: without a connection the
 * screens say so instead of showing anything old.
 */

/** Only a real signed-in account has a calendar (not the developer preview, which has no tokens). */
export function useCalendarEnabled(): boolean {
  const { status, preview } = useSession();
  return status === 'signedIn' && !preview;
}

const rangeQuery = (filter: CalendarFilter, range: DateRange) => ({
  queryKey: itemsKey(filter, range),
  queryFn: () => fetchCalendarRange(filter, range),
  staleTime: CALENDAR_STALE_MS,
  gcTime: CALENDAR_GC_MS,
});

/**
 * The items of the days `range` shows, for a filter. While the next page of the same filter loads, the previous page stays on
 * screen; a change of filter (another child, school or source) never shows the previous filter's items.
 */
export function useCalendarRange(filter: CalendarFilter, range: DateRange) {
  const enabled = useCalendarEnabled();
  const key = itemsKey(filter, range);
  return useQuery({
    ...rangeQuery(filter, range),
    enabled,
    placeholderData: (previous, previousQuery) => (previousQuery && sameFilter(previousQuery.queryKey, key) ? previous : undefined),
  });
}

/** Asks for the neighbouring ranges ahead of time so a swipe to the next month finds its data. */
export function usePrefetchRanges() {
  const queryClient = useQueryClient();
  return useCallback(
    (filter: CalendarFilter, ranges: readonly DateRange[]) => {
      for (const range of ranges) void queryClient.prefetchQuery(rangeQuery(filter, range)).catch(() => undefined);
    },
    [queryClient],
  );
}

/** One event as the caller sees it (a parent meeting only if invited or the organiser). */
export function useEvent(eventId: string | undefined) {
  const enabled = useCalendarEnabled();
  return useQuery({
    queryKey: eventKey(eventId ?? ''),
    enabled: enabled && !!eventId,
    queryFn: () => fetchEvent(eventId!),
    gcTime: CALENDAR_GC_MS,
    staleTime: 10_000,
    retry: false,
  });
}

/** What the caller may create in a school (menus and buttons; the API stays the authority). */
export function useCalendarCapabilities(schoolId: string | undefined) {
  const enabled = useCalendarEnabled();
  return useQuery({
    queryKey: capabilitiesKey(schoolId ?? ''),
    enabled: enabled && !!schoolId,
    queryFn: () => fetchCapabilities(schoolId!),
    staleTime: 2 * 60_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
}

/** Every active class of a school, for managers whose capabilities do not list classes (they may use any). */
export function useAllClasses(schoolId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: classChoicesKey(schoolId ?? ''),
    enabled: enabled && !!schoolId,
    queryFn: async () => (await fetchClasses(schoolId!, '')).items.map((cls) => ({ id: cls.id, name: cls.name })),
    gcTime: CALENDAR_GC_MS,
    staleTime: 2 * 60_000,
  });
}

/** The schools of the caller, by name (`GET /me` memberships), once each. */
export function useCalendarSchools(): { id: string; name: string }[] {
  const me = useMe();
  const seen = new Set<string>();
  const schools: { id: string; name: string }[] = [];
  for (const membership of me.data?.memberships ?? []) {
    if (seen.has(membership.schoolId)) continue;
    seen.add(membership.schoolId);
    schools.push({ id: membership.schoolId, name: membership.schoolName });
  }
  return schools;
}

// --- keeping the caches in step ---------------------------------------------------------------------

/** Every window starts again from the server (an event was created, changed, cancelled or deleted, or a push says so). */
export function refreshCalendar(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: [CALENDAR_ROOT] });
}

/** The API's answer for an event replaces what the detail holds, and the windows are asked again. */
export function replaceEvent(queryClient: QueryClient, view: CalendarEventView): void {
  queryClient.setQueryData(eventKey(view.id), view);
  void queryClient.invalidateQueries({ queryKey: [CALENDAR_ROOT, 'items'] });
}

/** After a delete: the detail is gone, the windows are asked again. */
export function forgetEvent(queryClient: QueryClient, eventId: string): void {
  queryClient.removeQueries({ queryKey: eventKey(eventId) });
  void queryClient.invalidateQueries({ queryKey: [CALENDAR_ROOT, 'items'] });
}
