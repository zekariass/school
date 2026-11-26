"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useSyncExternalStore } from "react";
import { fetchCalendarCapabilities, fetchEvent, ck, CALENDAR_GC_MS } from "@/lib/calendar/queries";
import { isoInZone } from "@/lib/calendar/dates";

const subscribeNothing = () => () => {};

/**
 * The viewer's own time zone as the browser reports it, or null before hydration. Used for two things only: the date of
 * "today" and the note that an event is in another zone. Events are always shown in their own zone, explicitly.
 */
export function useViewerTimeZone(): string | null {
  return useSyncExternalStore(
    subscribeNothing,
    () => {
      try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
      } catch {
        return "UTC";
      }
    },
    () => null,
  );
}

/** Today's date ("yyyy-mm-dd") in the viewer's zone; null before hydration. */
export function useToday(): string | null {
  const zone = useViewerTimeZone();
  // Read once per render: the page is not left open across midnight for long, and any navigation re-renders.
  return zone ? isoInZone(new Date(), zone) : null;
}

/** True under the `sm` breakpoint (640px): the month grid collapses to a list there. Null before hydration. */
export function useIsNarrow(): boolean | null {
  const subscribe = useCallback((onChange: () => void) => {
    const query = window.matchMedia("(max-width: 639px)");
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia("(max-width: 639px)").matches,
    () => null,
  );
}

/** One event (series) with its rule, exceptions, the caller's answer and what they may do. Memory only, a short life. */
export function useEvent(eventId: string) {
  return useQuery({
    queryKey: ck.event(eventId),
    queryFn: () => fetchEvent(eventId),
    staleTime: 15_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
}

/** What the caller may create in a school (for menus; the API decides). */
export function useCalendarCapabilities(schoolId: string | null) {
  return useQuery({
    queryKey: ck.capabilities(schoolId ?? "none"),
    queryFn: () => fetchCalendarCapabilities(schoolId!),
    enabled: !!schoolId,
    staleTime: 60_000,
    gcTime: CALENDAR_GC_MS,
    retry: false,
  });
}

/** Refetches every calendar window and event after a write. */
export function useRefreshCalendar() {
  const queryClient = useQueryClient();
  return useCallback(() => queryClient.invalidateQueries({ queryKey: ck.all }), [queryClient]);
}
