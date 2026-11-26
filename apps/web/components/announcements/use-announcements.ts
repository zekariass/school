"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";
import { ank, fetchCapabilities, fetchSummary } from "@/lib/announcements/queries";

/** The unread and waiting-for-acknowledgement counts of the header badge and the inbox. */
export function useAnnouncementSummary(enabled = true) {
  return useQuery({
    queryKey: ank.summary,
    queryFn: fetchSummary,
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}

/** What the caller may write in a school: audience kinds, urgent, channels and the school's time zone (for buttons; the API decides). */
export function useAnnouncementCapabilities(schoolId: string, enabled = true) {
  return useQuery({
    queryKey: ank.capabilities(schoolId),
    queryFn: () => fetchCapabilities(schoolId),
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}

/** Refetches every announcement list, detail and badge (after a write). */
export function useRefreshAnnouncements() {
  const queryClient = useQueryClient();
  return useCallback(() => queryClient.invalidateQueries({ queryKey: ank.all }), [queryClient]);
}
