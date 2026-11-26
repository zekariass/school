import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';

import { flattenPages, type Pages } from '@/lib/feed/pages';
import { useSession } from '@/lib/session/session-provider';

import { pollIntervalMs } from './doorbell';
import {
  MESSAGING_GC_MS,
  blocksKey,
  capabilitiesKey,
  contactsKey,
  conversationKey,
  groupsKey,
  inboxKey,
  membersKey,
  messagesKey,
  nextMessagingPage,
  scheduledKey,
  unreadCountKey,
} from './keys';
import { liveStatus } from './live-status';
import { flattenMessages, type MessageData } from './pages';
import {
  fetchBlocks,
  fetchCapabilities,
  fetchContacts,
  fetchConversation,
  fetchGroups,
  fetchInbox,
  fetchMembers,
  fetchMessages,
  fetchScheduled,
  fetchUnreadCount,
} from './requests';
import { refreshLatest } from './runtime';
import type { ConversationPage, ConversationSummary, MessagePage } from './types';

/**
 * Everything here is memory-only: the keys sit under `messaging`, which `isPersistableMessagingQuery` keeps out of the persisted
 * cache, and a page that nobody uses is dropped after three minutes. While the realtime socket is down the screens poll.
 */

/** Only a real signed-in account has messaging (not the developer preview, which has no tokens). */
export function useMessagingEnabled(): boolean {
  const { status, preview } = useSession();
  return status === 'signedIn' && !preview;
}

/** Whether the doorbell can ring right now. */
export function useLive(): boolean {
  return useSyncExternalStore(liveStatus.subscribe, liveStatus.get, liveStatus.get);
}

/** The unread total for the tab badge. A number only. */
export function useUnreadCount() {
  const enabled = useMessagingEnabled();
  const live = useLive();
  return useQuery({
    queryKey: unreadCountKey,
    enabled,
    queryFn: fetchUnreadCount,
    staleTime: 20_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
    refetchInterval: pollIntervalMs(live, 'badge'),
  });
}

/** The inbox: conversations you belong to or observe, newest activity first. `studentId` limits it to one child's. */
export function useInbox(schoolId: string | null, studentId: string | null) {
  const enabled = useMessagingEnabled();
  const live = useLive();
  const query = useInfiniteQuery({
    queryKey: inboxKey(schoolId, studentId),
    enabled,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: ConversationPage, all) => nextMessagingPage(last, all.length),
    queryFn: ({ pageParam }) => fetchInbox(schoolId, studentId, pageParam),
    gcTime: MESSAGING_GC_MS,
    staleTime: 15_000,
    refetchInterval: pollIntervalMs(live, 'inbox'),
  });
  const items: ConversationSummary[] = flattenPages(query.data as Pages<ConversationSummary> | undefined);
  return { query, items };
}

export function useConversation(conversationId: string | undefined) {
  const live = useLive();
  return useQuery({
    queryKey: conversationKey(conversationId ?? ''),
    enabled: !!conversationId,
    queryFn: () => fetchConversation(conversationId!),
    gcTime: MESSAGING_GC_MS,
    staleTime: 15_000,
    retry: false,
    refetchInterval: pollIntervalMs(live, 'conversation'),
  });
}

export function useMembers(conversationId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: membersKey(conversationId ?? ''),
    enabled: enabled && !!conversationId,
    queryFn: () => fetchMembers(conversationId!),
    gcTime: MESSAGING_GC_MS,
    staleTime: 30_000,
  });
}

/** The messages of a conversation, newest first; older pages load as the person scrolls up. */
export function useMessages(conversationId: string | undefined) {
  const query = useInfiniteQuery({
    queryKey: messagesKey(conversationId ?? ''),
    enabled: !!conversationId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: MessagePage, all) => (last.nextCursor && all.length < MAX_MESSAGE_PAGES ? last.nextCursor : undefined),
    queryFn: ({ pageParam }) => fetchMessages(conversationId!, pageParam),
    gcTime: MESSAGING_GC_MS,
    // The doorbell and the polling keep it current; a re-focus should not refetch every page.
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
  const messages = flattenMessages(query.data as MessageData | undefined);
  return { query, messages };
}

/** Pages of 30 messages kept per conversation: 600 messages, then older ones need leaving and coming back. */
export const MAX_MESSAGE_PAGES = 20;

/**
 * While the socket is down, asks for the newest page of the open conversation every few seconds and folds it in; also once when
 * the app returns to the foreground. (With the socket up the doorbell does this by id.)
 */
export function useConversationPolling(conversationId: string | undefined): void {
  const client = useQueryClient();
  const live = useLive();
  useEffect(() => {
    if (!conversationId) return;
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refreshLatest(client, conversationId);
    });
    const interval = pollIntervalMs(live, 'conversation');
    const timer = interval === false ? null : setInterval(() => void refreshLatest(client, conversationId), interval);
    return () => {
      subscription.remove();
      if (timer) clearInterval(timer);
    };
  }, [client, conversationId, live]);
}

export function useMessagingCapabilities(schoolId: string | undefined) {
  const enabled = useMessagingEnabled();
  return useQuery({
    queryKey: capabilitiesKey(schoolId ?? ''),
    enabled: enabled && !!schoolId,
    queryFn: () => fetchCapabilities(schoolId!),
    staleTime: 2 * 60_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

export function useContacts(schoolId: string | undefined, studentId: string | undefined, audience: 'PARENTS' | null = null, enabled = true) {
  return useQuery({
    queryKey: contactsKey(schoolId ?? '', studentId ?? '', audience ?? 'default'),
    enabled: enabled && !!schoolId && !!studentId,
    queryFn: () => fetchContacts(schoolId!, studentId!, audience ?? undefined),
    staleTime: 30_000,
    gcTime: MESSAGING_GC_MS,
    retry: false,
  });
}

export function useScheduled() {
  const enabled = useMessagingEnabled();
  return useQuery({ queryKey: scheduledKey, enabled, queryFn: fetchScheduled, staleTime: 10_000, gcTime: MESSAGING_GC_MS });
}

export function useGroups(schoolId: string | undefined, status: string | null) {
  return useQuery({
    queryKey: groupsKey(schoolId ?? '', status ?? 'all'),
    enabled: !!schoolId,
    queryFn: () => fetchGroups(schoolId!, status),
    staleTime: 10_000,
    gcTime: MESSAGING_GC_MS,
  });
}

export function useBlocks() {
  return useQuery({ queryKey: blocksKey, queryFn: fetchBlocks, staleTime: 10_000, gcTime: MESSAGING_GC_MS });
}
