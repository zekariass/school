"use client";

import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useSyncExternalStore } from "react";
import { pollInterval } from "@/lib/messaging/doorbell";
import { mk, MESSAGING_GC_MS } from "@/lib/messaging/keys";
import {
  fetchBlocks,
  fetchCapabilities,
  fetchConversation,
  fetchContacts,
  fetchGroups,
  fetchInbox,
  fetchMembers,
  fetchMessages,
  fetchScheduled,
  fetchUnread,
} from "@/lib/messaging/queries";
import { useSocketUp } from "@/lib/messaging/socket-status";

function subscribeVisibility(onChange: () => void) {
  document.addEventListener("visibilitychange", onChange);
  return () => document.removeEventListener("visibilitychange", onChange);
}

/** True while the tab is hidden (nothing to read, nothing to mark read). */
export function useDocumentHidden(): boolean {
  return useSyncExternalStore(
    subscribeVisibility,
    () => document.hidden,
    () => false,
  );
}

/** The refetch interval for live data: slow while the socket delivers doorbells, fast without it, none in a hidden tab. */
export function usePollInterval(): number | false {
  const socketUp = useSocketUp();
  const hidden = useDocumentHidden();
  return pollInterval({ socketUp, hidden });
}

/** The header badge: unread messages over every conversation the caller may use now (the API computes it). */
export function useUnreadCount(enabled = true) {
  const interval = usePollInterval();
  return useQuery({
    queryKey: mk.unread,
    queryFn: fetchUnread,
    enabled,
    staleTime: 30_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
    refetchInterval: interval,
  });
}

/** The inbox, newest activity first, keyset paged. `schoolId` limits it to one school (the workspace), `studentId` to one child. */
export function useInbox(schoolId: string | null, studentId: string | null = null) {
  const interval = usePollInterval();
  return useInfiniteQuery({
    queryKey: mk.inbox(schoolId, studentId),
    queryFn: ({ pageParam }) => fetchInbox(schoolId, studentId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 15_000,
    gcTime: MESSAGING_GC_MS,
    refetchInterval: interval,
  });
}

export function useConversation(conversationId: string) {
  const interval = usePollInterval();
  return useQuery({
    queryKey: mk.conversation(conversationId),
    queryFn: () => fetchConversation(conversationId),
    staleTime: 15_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
    refetchInterval: interval,
  });
}

export function useMembers(conversationId: string, enabled: boolean) {
  return useQuery({
    queryKey: mk.members(conversationId),
    queryFn: () => fetchMembers(conversationId),
    enabled,
    staleTime: 15_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

/**
 * The messages of a conversation, newest page first (`getNextPageParam` goes to older ones). Gone from memory as soon as the
 * conversation is left (`gcTime: 0`): message text is not kept around.
 */
export function useMessages(conversationId: string) {
  const interval = usePollInterval();
  return useInfiniteQuery({
    queryKey: mk.messages(conversationId),
    queryFn: ({ pageParam }) => fetchMessages(conversationId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 5_000,
    gcTime: 0,
    retry: false,
    refetchInterval: interval,
  });
}

/** What the caller may do in a school's messaging (it drives every control; the API decides). */
export function useCapabilities(schoolId: string | undefined) {
  return useQuery({
    queryKey: mk.capabilities(schoolId ?? "none"),
    queryFn: () => fetchCapabilities(schoolId!),
    enabled: !!schoolId,
    staleTime: 60_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

export function useContacts(schoolId: string | undefined, studentId: string | undefined, audience: "TEACHERS" | "PARENTS", enabled = true) {
  return useQuery({
    queryKey: mk.contacts(schoolId ?? "none", studentId ?? "none", audience),
    queryFn: () => fetchContacts(schoolId!, studentId!, audience === "PARENTS" ? "PARENTS" : undefined),
    enabled: enabled && !!schoolId && !!studentId,
    staleTime: 30_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

export function useScheduled() {
  const interval = usePollInterval();
  return useQuery({ queryKey: mk.scheduled, queryFn: fetchScheduled, staleTime: 15_000, gcTime: MESSAGING_GC_MS, retry: false, refetchInterval: interval });
}

export function useBlocks() {
  return useQuery({ queryKey: mk.blocks, queryFn: fetchBlocks, staleTime: 15_000, gcTime: MESSAGING_GC_MS, retry: false });
}

export function useGroups(schoolId: string, status: string | undefined, enabled = true) {
  return useQuery({
    queryKey: mk.groups(schoolId, status ?? "all"),
    queryFn: () => fetchGroups(schoolId, status),
    enabled,
    staleTime: 15_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

/** Refetches everything messaging shows (after a write that touches the inbox, the badge or a conversation). */
export function useRefreshMessaging() {
  const queryClient = useQueryClient();
  return useCallback(() => queryClient.invalidateQueries({ queryKey: mk.all }), [queryClient]);
}
