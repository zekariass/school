import { useInfiniteQuery, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { firstFound, useCandidateSchoolIds } from '@/lib/feed/queries';
import { unreadCountKey } from '@/lib/notifications/keys';
import { useInboxActions } from '@/lib/notifications/queries';
import { flattenPages, mapItem, type Pages } from '@/lib/feed/pages';
import { useSession } from '@/lib/session/session-provider';

import { ackWithCard, readWithCard } from './cards';
import { FEATURED_LOOKAHEAD, pickFeatured } from './featured';
import { notificationIdsFor } from './notifications';
import { withAcknowledged, withRead } from './ack';
import {
  ANNOUNCEMENTS_GC_MS,
  ANNOUNCEMENTS_ROOT,
  anywhereKey,
  authoredKey,
  capabilitiesKey,
  channelsKey,
  detailKey,
  featuredKey,
  inboxKey,
  nextPage,
  recipientsKey,
  reportKey,
  summaryKey,
  type InboxFilter,
  type InboxSort,
} from './keys';
import {
  acknowledge,
  fetchAnnouncement,
  fetchAuthored,
  fetchCapabilities,
  fetchChannels,
  fetchInbox,
  fetchRecipients,
  fetchReport,
  fetchSummary,
  markRead,
} from './requests';
import type { AnnouncementCard, AnnouncementPage, AnnouncementStatus, AnnouncementView, RecipientFilter, RecipientPage } from './types';

/**
 * Everything here is memory-only: the keys sit under `announcements`, which `isPersistableAnnouncementQuery` keeps out of the
 * persisted cache, and a page that nobody uses is dropped after five minutes.
 */

/** Only a real signed-in account has announcements (not the developer preview, which has no tokens). */
function useEnabled(): boolean {
  const { status, preview } = useSession();
  return status === 'signedIn' && !preview;
}

/** The unread and needs-acknowledgement counts for the badge. Counts only; they are never stored. */
export function useAnnouncementSummary() {
  const enabled = useEnabled();
  return useQuery({ queryKey: summaryKey, enabled, queryFn: fetchSummary, staleTime: 30_000, gcTime: ANNOUNCEMENTS_GC_MS });
}

function usePages(key: readonly unknown[], load: (cursor: string | undefined) => Promise<AnnouncementPage>, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: key,
    enabled,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: AnnouncementPage, all) => nextPage(last, all.length),
    queryFn: ({ pageParam }) => load(pageParam),
    gcTime: ANNOUNCEMENTS_GC_MS,
    staleTime: 20_000,
  });
  return { query, cards: flattenPages(query.data as Pages<AnnouncementCard> | undefined) };
}

export function useInbox(schoolId: string | null, filter: InboxFilter, sort: InboxSort) {
  const enabled = useEnabled();
  return usePages(inboxKey(schoolId, filter, sort), (cursor) => fetchInbox(schoolId, filter, sort, cursor), enabled);
}

/**
 * The announcement shown on top of a feed: the newest published one of the person's inbox (`null` when none). One small page
 * (the newest few cards), memory only; `schoolId` narrows it to the school of a class feed.
 */
export function useFeaturedAnnouncement(schoolId: string | null) {
  const enabled = useEnabled();
  return useQuery({
    queryKey: featuredKey(schoolId),
    enabled,
    queryFn: async () => pickFeatured((await fetchInbox(schoolId, 'ALL', 'NEWEST', undefined, FEATURED_LOOKAHEAD)).items),
    staleTime: 30_000,
    gcTime: ANNOUNCEMENTS_GC_MS,
  });
}

export function useAuthored(schoolId: string | undefined, status: AnnouncementStatus | null, mine: boolean) {
  return usePages(authoredKey(schoolId ?? '', status, mine), (cursor) => fetchAuthored(schoolId!, status, mine, cursor), !!schoolId);
}

export function useAnnouncementCapabilities(schoolId: string | undefined) {
  return useQuery({
    queryKey: capabilitiesKey(schoolId ?? ''),
    enabled: !!schoolId,
    queryFn: () => fetchCapabilities(schoolId!),
    staleTime: 2 * 60_000,
    gcTime: ANNOUNCEMENTS_GC_MS,
    retry: false,
  });
}

export function useAnnouncementQuery(schoolId: string | undefined, announcementId: string | undefined) {
  return useQuery({
    queryKey: detailKey(schoolId ?? '', announcementId ?? ''),
    enabled: !!schoolId && !!announcementId,
    queryFn: () => fetchAnnouncement(schoolId!, announcementId!),
    gcTime: ANNOUNCEMENTS_GC_MS,
    staleTime: 10_000,
  });
}

/**
 * An announcement by id alone: a push names a school (`data.schoolId`) and the screen asks it first; only without one, or after
 * a miss, the caller's schools are asked in turn. `null` when none has it.
 */
export function useAnnouncementAnywhere(announcementId: string | undefined, enabled: boolean) {
  const { ids, ready } = useCandidateSchoolIds(null);
  return useQuery({
    queryKey: anywhereKey(announcementId ?? '', ids.join(',')),
    enabled: enabled && !!announcementId && ready,
    queryFn: () => firstFound(ids, (schoolId) => fetchAnnouncement(schoolId, announcementId!)),
    gcTime: ANNOUNCEMENTS_GC_MS,
    retry: false,
  });
}

export function useReport(schoolId: string | undefined, announcementId: string | undefined) {
  return useQuery({
    queryKey: reportKey(schoolId ?? '', announcementId ?? ''),
    enabled: !!schoolId && !!announcementId,
    queryFn: () => fetchReport(schoolId!, announcementId!),
    gcTime: ANNOUNCEMENTS_GC_MS,
    staleTime: 15_000,
  });
}

/** The recipients behind a report filter (names: kept in memory only, dropped five minutes after the screen closes). */
export function useRecipients(schoolId: string | undefined, announcementId: string | undefined, filter: RecipientFilter) {
  const query = useInfiniteQuery({
    queryKey: recipientsKey(schoolId ?? '', announcementId ?? '', filter),
    enabled: !!schoolId && !!announcementId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: RecipientPage, all) => nextPage(last, all.length),
    queryFn: ({ pageParam }) => fetchRecipients(schoolId!, announcementId!, filter, pageParam),
    gcTime: ANNOUNCEMENTS_GC_MS,
    staleTime: 15_000,
  });
  const seen = new Set<string>();
  const recipients = (query.data?.pages ?? []).flatMap((page) => page.items).filter((item) => !seen.has(item.userId) && !!seen.add(item.userId));
  return { query, recipients };
}

export function useChannelPreferences() {
  const enabled = useEnabled();
  return useQuery({ queryKey: channelsKey, enabled, queryFn: fetchChannels, staleTime: 60_000, gcTime: ANNOUNCEMENTS_GC_MS });
}

// --- keeping the caches in step ---------------------------------------------------------------------

function isPages(data: unknown): data is Pages<{ id: string }> {
  return typeof data === 'object' && data !== null && Array.isArray((data as { pages?: unknown }).pages);
}

/** Applies `change` to the card of `id` in every list (inbox and authored) so the lists and the open screen agree. */
export function updateCardEverywhere(queryClient: QueryClient, id: string, change: (card: AnnouncementCard) => AnnouncementCard): void {
  queryClient.setQueriesData({ queryKey: [ANNOUNCEMENTS_ROOT] }, (data: unknown) =>
    isPages(data) ? mapItem(data as Pages<AnnouncementCard>, id, change) : data,
  );
}

/** Every list starts again from the server (a new, changed, withdrawn or removed announcement). */
export function refreshAnnouncements(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: [ANNOUNCEMENTS_ROOT] });
}

/** The answer of the API for an announcement replaces what the detail and the cards hold. */
export function replaceAnnouncement(queryClient: QueryClient, view: AnnouncementView): void {
  queryClient.setQueryData(detailKey(view.schoolId, view.id), view);
  updateCardEverywhere(queryClient, view.id, (card) => ({
    ...card,
    title: view.title,
    status: view.status,
    type: view.type,
    revision: view.revision,
    requiresAcknowledgement: view.requiresAcknowledgement,
    ...(view.mine.readAt ? { readAt: view.mine.readAt } : {}),
    ...(view.mine.acknowledgedAt ? { acknowledgedAt: view.mine.acknowledgedAt } : {}),
    ...(view.withdrawnAt ? { withdrawnAt: view.withdrawnAt } : {}),
  }));
}

/**
 * Marks the announcement read on the server once per opening, and everywhere on screen at once (the lists lose their dot, the
 * counts drop). The notification of the announcement is a separate record: the notification screens and a tapped push mark
 * theirs read; this call does not touch it.
 */
export function useMarkRead() {
  const queryClient = useQueryClient();
  return useCallback(
    async (view: Pick<AnnouncementView, 'id' | 'schoolId' | 'status' | 'mine'>) => {
      // Only a person who was addressed has a read to record; someone who found it in the school's list has not.
      if (view.status !== 'PUBLISHED' || !view.mine.recipient || view.mine.read) return;
      const now = new Date().toISOString();
      queryClient.setQueryData<AnnouncementView>(detailKey(view.schoolId, view.id), (current) => (current ? withRead(current, now) : current));
      updateCardEverywhere(queryClient, view.id, (card) => readWithCard(card, now));
      try {
        await markRead(view.schoolId, view.id);
      } finally {
        void queryClient.invalidateQueries({ queryKey: summaryKey });
      }
    },
    [queryClient],
  );
}

/** Acknowledges (idempotent on the server) and puts the answer everywhere; a failure leaves everything as it was. */
export function useAcknowledge() {
  const queryClient = useQueryClient();
  return useCallback(
    async (view: AnnouncementView): Promise<AnnouncementView> => {
      const before = view;
      const now = new Date().toISOString();
      queryClient.setQueryData(detailKey(view.schoolId, view.id), withAcknowledged(view, now));
      updateCardEverywhere(queryClient, view.id, (card) => ackWithCard(card, now));
      try {
        const answer = await acknowledge(view.schoolId, view.id);
        replaceAnnouncement(queryClient, answer);
        return answer;
      } catch (error) {
        queryClient.setQueryData(detailKey(before.schoolId, before.id), before);
        void queryClient.invalidateQueries({ queryKey: [ANNOUNCEMENTS_ROOT, 'inbox'] });
        throw error;
      } finally {
        void queryClient.invalidateQueries({ queryKey: summaryKey });
      }
    },
    [queryClient],
  );
}

/**
 * Marks the notifications of an announcement read (see `notificationIdsFor`). Looks only when the bell has something unread, in
 * the newest 50 unread notifications; a failure is not worth a message (the notification list is still right on the server).
 */
export function useReadAnnouncementNotifications() {
  const queryClient = useQueryClient();
  const { markRead } = useInboxActions();
  return useCallback(
    async (announcementId: string) => {
      if (queryClient.getQueryData<number>(unreadCountKey) === 0) return;
      const page = await request(async () => unwrap(await api.GET('/api/v1/notifications', { params: { query: { unreadOnly: true, limit: 50 } } })));
      for (const id of notificationIdsFor(page.items, announcementId)) markRead.mutate(id);
    },
    [queryClient, markRead],
  );
}
