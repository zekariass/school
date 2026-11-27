import { useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query';

import { firstFound, useCandidateSchoolIds } from '@/lib/feed/queries';
import { flattenPages, mapItem, removeItem, type Pages } from '@/lib/feed/pages';

import {
  PORTFOLIO_GC_MS,
  PORTFOLIO_ROOT,
  capabilitiesKey,
  childrenKey,
  commentsKey,
  historyKey,
  itemKey,
  mineKey,
  nextPortfolioPage,
  queueKey,
} from './keys';
import { fetchCapabilities, fetchChildren, fetchComments, fetchHistory, fetchItem, fetchMine, fetchQueue } from './requests';
import type { PortfolioComment, PortfolioCommentPage, PortfolioItem, PortfolioPage, PortfolioStatus } from './types';

/**
 * Everything here is memory-only: the keys sit under `portfolio`, which `isPersistablePortfolioQuery` keeps out of the persisted
 * cache, and a page that nobody uses is dropped after three minutes. `networkMode: 'online'` pauses a query while the phone is
 * offline, so a screen opened without a connection has nothing to show and says that it needs one (it never falls back to an
 * old copy: there is none).
 */
const MEMORY = { gcTime: PORTFOLIO_GC_MS, networkMode: 'online' } as const;

function useItemPages(key: readonly unknown[], load: (cursor: string | undefined) => Promise<PortfolioPage>, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: key,
    enabled,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: PortfolioPage, all) => nextPortfolioPage(last, all.length),
    queryFn: ({ pageParam }) => load(pageParam),
    staleTime: 30_000,
    ...MEMORY,
  });
  return { query, items: flattenPages(query.data as Pages<PortfolioItem> | undefined) };
}

/** The signed-in student's own items, in every status (optionally one status). */
export const useMyItems = (schoolId: string | undefined, status: PortfolioStatus | null) =>
  useItemPages(mineKey(schoolId ?? '', status), (cursor) => fetchMine(schoolId!, status, cursor), !!schoolId);

/** SUBMITTED items of a class, oldest first, for its reviewers. */
export const useReviewQueue = (schoolId: string | undefined, classId: string | undefined) =>
  useItemPages(queueKey(schoolId ?? '', classId ?? ''), (cursor) => fetchQueue(schoolId!, classId!, cursor), !!schoolId && !!classId);

/** A guardian's children: approved, guardian-visible items only (the API filters; nothing is filtered here). */
export const useChildrenItems = (studentId: string | null, enabled = true) =>
  useItemPages(childrenKey(studentId), (cursor) => fetchChildren(studentId, cursor), enabled);

/** What the caller may do in a school's portfolio: create, capture (which classes), review, the audience cap. */
export function usePortfolioCapabilities(schoolId: string | undefined) {
  return useQuery({
    queryKey: capabilitiesKey(schoolId ?? ''),
    enabled: !!schoolId,
    queryFn: () => fetchCapabilities(schoolId!),
    staleTime: 2 * 60_000,
    ...MEMORY,
  });
}

export function useItemQuery(schoolId: string | undefined, itemId: string | undefined) {
  return useQuery({
    queryKey: itemKey(schoolId ?? '', itemId ?? ''),
    enabled: !!schoolId && !!itemId,
    queryFn: () => fetchItem(schoolId!, itemId!),
    staleTime: 15_000,
    ...MEMORY,
  });
}

/** An item by id alone (a notification may name no school): asks the caller's schools in turn. `null` when none has it. */
export function useItemAnywhere(itemId: string | undefined, hint?: string | null, enabled = true) {
  const { ids, ready } = useCandidateSchoolIds(hint);
  return useQuery({
    queryKey: [PORTFOLIO_ROOT, 'item-any', itemId ?? '', ids.join(',')],
    enabled: enabled && !!itemId && ready,
    queryFn: () => firstFound(ids, (schoolId) => fetchItem(schoolId, itemId!)),
    retry: false,
    ...MEMORY,
  });
}

/** The status history: for the student and the reviewers only (the API answers 403 to anybody else, so `enabled` follows the item). */
export function useHistory(schoolId: string | undefined, itemId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: historyKey(schoolId ?? '', itemId ?? ''),
    enabled: enabled && !!schoolId && !!itemId,
    queryFn: () => fetchHistory(schoolId!, itemId!),
    retry: false,
    ...MEMORY,
  });
}

export function useComments(schoolId: string | undefined, itemId: string | undefined, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: commentsKey(schoolId ?? '', itemId ?? ''),
    enabled: enabled && !!schoolId && !!itemId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: PortfolioCommentPage, all) => nextPortfolioPage(last, all.length),
    queryFn: ({ pageParam }) => fetchComments(schoolId!, itemId!, pageParam),
    staleTime: 15_000,
    ...MEMORY,
  });
  return { query, comments: flattenPages(query.data as Pages<PortfolioComment> | undefined) };
}

// --- keeping the caches in step ---------------------------------------------------------------------------

function isPages(data: unknown): data is Pages<{ id: string }> {
  return typeof data === 'object' && data !== null && Array.isArray((data as { pages?: unknown }).pages);
}

/** Replaces the item in every list and in its own query with what the API answered (the lists and the item page agree). */
export function replaceItem(queryClient: QueryClient, item: PortfolioItem): void {
  queryClient.setQueriesData({ queryKey: [PORTFOLIO_ROOT] }, (data: unknown) => {
    if (isPages(data)) return mapItem(data as Pages<PortfolioItem>, item.id, () => item);
    if (data && typeof data === 'object' && (data as { id?: unknown }).id === item.id && 'capabilities' in data) return item;
    return data;
  });
  queryClient.setQueryData(itemKey(item.schoolId, item.id), item);
}

/** Takes an item out of every list (deleted, reviewed out of the queue) and forgets its own query. */
export function removeItemEverywhere(queryClient: QueryClient, schoolId: string, itemId: string): void {
  queryClient.setQueriesData({ queryKey: [PORTFOLIO_ROOT] }, (data: unknown) => (isPages(data) ? removeItem(data as Pages<PortfolioItem>, itemId) : data));
  queryClient.removeQueries({ queryKey: itemKey(schoolId, itemId) });
}

/** An item that left the review queue (it was decided): taken out of every queue at once, before the lists refresh. */
export function removeFromQueues(queryClient: QueryClient, itemId: string): void {
  queryClient.setQueriesData({ queryKey: [PORTFOLIO_ROOT, 'queue'] }, (data: unknown) => (isPages(data) ? removeItem(data as Pages<PortfolioItem>, itemId) : data));
}

/** A new or changed item: every list starts again from the server. */
export function refreshPortfolio(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: [PORTFOLIO_ROOT] });
}
