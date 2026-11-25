import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';
import { useSession } from '@/lib/session/session-provider';

import { dropRead, markItemsRead, removeItem, type InboxData } from './cache';
import type { InboxItem } from './events';
import { INBOX_PREFIX, inboxKey, preferencesKey, settingsKey, unreadCountKey } from './keys';

export const PAGE_SIZE = 20;

export const unreadCountQuery = queryOptions({
  queryKey: unreadCountKey,
  queryFn: async () => (await request(async () => unwrap(await api.GET('/api/v1/notifications/unread-count')))).count,
  staleTime: 30_000,
});

const inboxQuery = (unreadOnly: boolean) =>
  infiniteQueryOptions({
    queryKey: inboxKey(unreadOnly),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      request(async () =>
        unwrap(
          await api.GET('/api/v1/notifications', {
            params: { query: { cursor: pageParam, limit: PAGE_SIZE, unreadOnly } },
          }),
        ),
      ) as Promise<{ items: InboxItem[]; nextCursor?: string }>,
    getNextPageParam: (last) => last.nextCursor,
    staleTime: 15_000,
  });

/** Only a real signed-in account has an inbox (not the developer preview, which has no tokens). */
function useInboxEnabled(): boolean {
  const { status, preview } = useSession();
  return status === 'signedIn' && !preview;
}

export function useUnreadCount() {
  const enabled = useInboxEnabled();
  return useQuery({ ...unreadCountQuery, enabled });
}

export function useInbox(unreadOnly = false) {
  const enabled = useInboxEnabled();
  return useInfiniteQuery({ ...inboxQuery(unreadOnly), enabled });
}

/** Optimistic read/archive updates, with the server's count reconciled afterwards. */
export function useInboxActions() {
  const client = useQueryClient();

  function patchInboxes(update: (data: InboxData | undefined, unreadOnlyList: boolean) => InboxData | undefined) {
    for (const unreadOnly of [false, true]) {
      client.setQueryData<InboxData>(inboxKey(unreadOnly), (data) => update(data, unreadOnly));
    }
  }
  const reconcile = () => client.invalidateQueries({ queryKey: unreadCountKey });

  const markRead = useMutation({
    mutationFn: async (id: string) =>
      request(async () => assertOk(await api.POST('/api/v1/notifications/{id}/read', { params: { path: { id } } }))),
    onMutate: (id) => {
      const now = new Date().toISOString();
      patchInboxes((data, unreadOnly) => (unreadOnly ? dropRead(data, [id], false) : markItemsRead(data, [id], false, now)));
      client.setQueryData<number>(unreadCountKey, (count) => (count && count > 0 ? count - 1 : count));
    },
    onSettled: reconcile,
  });

  const markAllRead = useMutation({
    mutationFn: async () => request(async () => unwrap(await api.POST('/api/v1/notifications/read-all'))),
    onMutate: () => {
      const now = new Date().toISOString();
      patchInboxes((data, unreadOnly) => (unreadOnly ? dropRead(data, [], true) : markItemsRead(data, [], true, now)));
      client.setQueryData<number>(unreadCountKey, 0);
    },
    onSettled: reconcile,
  });

  const archive = useMutation({
    mutationFn: async (id: string) =>
      request(async () => assertOk(await api.POST('/api/v1/notifications/{id}/archive', { params: { path: { id } } }))),
    onMutate: (id) => patchInboxes((data) => removeItem(data, id)),
    onSettled: () => Promise.all([reconcile(), client.invalidateQueries({ queryKey: INBOX_PREFIX })]),
  });

  return { markRead, markAllRead, archive };
}

export const preferencesQuery = queryOptions({
  queryKey: preferencesKey,
  queryFn: async () => request(async () => unwrap(await api.GET('/api/v1/me/notification-preferences'))),
  staleTime: 60_000,
});

export const settingsQuery = queryOptions({
  queryKey: settingsKey,
  queryFn: async () => request(async () => unwrap(await api.GET('/api/v1/me/notification-settings'))),
  staleTime: 60_000,
});
