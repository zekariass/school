import { useInfiniteQuery, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';

import { ApiRequestError } from '@/lib/api-error';
import { useMe } from '@/lib/auth/use-me';
import { useMyChildren } from '@/lib/family/queries';
import { useSession } from '@/lib/session/session-provider';

import {
  FEED_GC_MS,
  FEED_ROOT,
  capabilitiesKey,
  classFeedKey,
  classStoriesKey,
  homeFeedKey,
  homeStoriesKey,
  myPostsKey,
  nextFeedPage,
  pendingKey,
  postKey,
  schoolFeedKey,
  schoolStoriesKey,
  storyKey,
  commentsKey,
} from './keys';
import { flattenPages, mapItem, removeItem, type Pages } from './pages';
import { applyReaction, withSummary } from './reactions';
import {
  fetchCapabilities,
  fetchClassFeed,
  fetchClassStories,
  fetchComments,
  fetchHomeFeed,
  fetchHomeStories,
  fetchMyPosts,
  fetchPending,
  fetchPost,
  fetchSchoolFeed,
  fetchSchoolStories,
  fetchStory,
  putReaction,
} from './requests';
import type { CommentPage, CommentView, PostPage, PostStatus, PostView, ReactionType, StoryView } from './types';

/**
 * Everything here is memory-only: the keys sit under `feed`, which `isPersistableFeedQuery` keeps out of the persisted
 * cache, and a page that nobody uses is dropped after five minutes.
 */

function useFeedPages(key: readonly unknown[], load: (cursor: string | undefined) => Promise<PostPage>, enabled: boolean) {
  const query = useInfiniteQuery({
    queryKey: key,
    enabled,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: PostPage, all) => nextFeedPage(last, all.length),
    queryFn: ({ pageParam }) => load(pageParam),
    gcTime: FEED_GC_MS,
    staleTime: 30_000,
  });
  return { query, posts: flattenPages(query.data as Pages<PostView> | undefined) };
}

export const useClassFeed = (schoolId: string | undefined, classId: string | undefined) =>
  useFeedPages(classFeedKey(schoolId ?? '', classId ?? ''), (cursor) => fetchClassFeed(schoolId!, classId!, cursor), !!schoolId && !!classId);

export const useSchoolFeed = (schoolId: string | undefined) =>
  useFeedPages(schoolFeedKey(schoolId ?? ''), (cursor) => fetchSchoolFeed(schoolId!, cursor), !!schoolId);

export const useHomeFeed = (studentId: string | null) => useFeedPages(homeFeedKey(studentId), (cursor) => fetchHomeFeed(studentId, cursor), true);

export const useMyPosts = (schoolId: string | undefined, status: PostStatus | null, classId: string | null) =>
  useFeedPages(myPostsKey(schoolId ?? '', status, classId), (cursor) => fetchMyPosts(schoolId!, status, classId, cursor), !!schoolId);

export const usePendingContributions = (schoolId: string | undefined, classId: string | null) =>
  useFeedPages(pendingKey(schoolId ?? '', classId), (cursor) => fetchPending(schoolId!, classId, cursor), !!schoolId);

/** What the caller may do in a school's feed, or in one class's (menus and buttons; the API stays the authority). */
export function useFeedCapabilities(schoolId: string | undefined, classId: string | null) {
  return useQuery({
    queryKey: capabilitiesKey(schoolId ?? '', classId),
    enabled: !!schoolId,
    queryFn: () => fetchCapabilities(schoolId!, classId),
    staleTime: 2 * 60_000,
    gcTime: FEED_GC_MS,
  });
}

export function usePostQuery(schoolId: string | undefined, postId: string | undefined) {
  return useQuery({
    queryKey: postKey(schoolId ?? '', postId ?? ''),
    enabled: !!schoolId && !!postId,
    queryFn: () => fetchPost(schoolId!, postId!),
    gcTime: FEED_GC_MS,
    staleTime: 15_000,
  });
}

export function useComments(schoolId: string | undefined, postId: string | undefined) {
  const query = useInfiniteQuery({
    queryKey: commentsKey(schoolId ?? '', postId ?? ''),
    enabled: !!schoolId && !!postId,
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: CommentPage, all) => nextFeedPage(last, all.length),
    queryFn: ({ pageParam }) => fetchComments(schoolId!, postId!, pageParam),
    gcTime: FEED_GC_MS,
    staleTime: 15_000,
  });
  return { query, comments: flattenPages(query.data as Pages<CommentView> | undefined) };
}

export const useClassStories = (schoolId: string | undefined, classId: string | undefined) =>
  useStories(classStoriesKey(schoolId ?? '', classId ?? ''), () => fetchClassStories(schoolId!, classId!), !!schoolId && !!classId);

export const useSchoolStories = (schoolId: string | undefined) =>
  useStories(schoolStoriesKey(schoolId ?? ''), () => fetchSchoolStories(schoolId!), !!schoolId);

export const useHomeStories = (studentId: string | null) => useStories(homeStoriesKey(studentId), () => fetchHomeStories(studentId), true);

function useStories(key: readonly unknown[], load: () => Promise<StoryView[]>, enabled: boolean) {
  return useQuery({ queryKey: key, enabled, queryFn: load, gcTime: FEED_GC_MS, staleTime: 30_000 });
}

export function useStoryQuery(schoolId: string | undefined, storyId: string | undefined) {
  return useQuery({
    queryKey: storyKey(schoolId ?? '', storyId ?? ''),
    enabled: !!schoolId && !!storyId,
    queryFn: () => fetchStory(schoolId!, storyId!),
    gcTime: FEED_GC_MS,
  });
}

// --- finding a post or story from a notification (only its id is known) ----------------------------------

/** The schools the caller belongs to (as staff, student or guardian) and their children's schools, a hint first. */
export function useCandidateSchoolIds(hint?: string | null): { ids: string[]; ready: boolean } {
  const me = useMe();
  const { activeRole } = useSession();
  const children = useMyChildren(activeRole === 'guardian');
  const ids = new Set<string>();
  if (hint) ids.add(hint);
  for (const membership of me.data?.memberships ?? []) ids.add(membership.schoolId);
  for (const child of children.data?.children ?? []) for (const school of child.schools) ids.add(school.schoolId);
  return { ids: [...ids], ready: !me.isPending };
}

async function firstFound<T>(schoolIds: string[], load: (schoolId: string) => Promise<T>): Promise<{ schoolId: string; value: T } | null> {
  for (const schoolId of schoolIds) {
    try {
      return { schoolId, value: await load(schoolId) };
    } catch (error) {
      // 404 (or 403) means "not in this school": try the next. Anything else is a real failure.
      if (error instanceof ApiRequestError && (error.status === 404 || error.status === 403)) continue;
      throw error;
    }
  }
  return null;
}

/** A post by id alone (a notification names no school): asks the caller's schools in turn. `null` when none has it. */
export function usePostAnywhere(postId: string | undefined, hint?: string | null) {
  const { ids, ready } = useCandidateSchoolIds(hint);
  return useQuery({
    queryKey: [FEED_ROOT, 'post-any', postId ?? '', ids.join(',')],
    enabled: !!postId && ready,
    queryFn: () => firstFound(ids, (schoolId) => fetchPost(schoolId, postId!)),
    gcTime: FEED_GC_MS,
    retry: false,
  });
}

export function useStoryAnywhere(storyId: string | undefined, hint?: string | null) {
  const { ids, ready } = useCandidateSchoolIds(hint);
  return useQuery({
    queryKey: [FEED_ROOT, 'story-any', storyId ?? '', ids.join(',')],
    enabled: !!storyId && ready,
    queryFn: () => firstFound(ids, (schoolId) => fetchStory(schoolId, storyId!)),
    gcTime: FEED_GC_MS,
    retry: false,
  });
}

// --- keeping the caches in step ---------------------------------------------------------------------------

function isPages(data: unknown): data is Pages<{ id: string }> {
  return typeof data === 'object' && data !== null && Array.isArray((data as { pages?: unknown }).pages);
}

/** Applies `change` to the post in every feed list and in its own query (a list, the post's page and my posts agree). */
export function updatePostEverywhere(queryClient: QueryClient, postId: string, change: (post: PostView) => PostView): void {
  queryClient.setQueriesData({ queryKey: [FEED_ROOT] }, (data: unknown) => {
    if (isPages(data)) return mapItem(data as Pages<PostView>, postId, change);
    if (data && typeof data === 'object' && (data as { id?: unknown }).id === postId && 'rights' in data) return change(data as PostView);
    return data;
  });
}

/** Takes a post out of every list (deleted, rejected, moved to another status) and forgets its own query. */
export function removePostEverywhere(queryClient: QueryClient, postId: string): void {
  queryClient.setQueriesData({ queryKey: [FEED_ROOT] }, (data: unknown) => (isPages(data) ? removeItem(data as Pages<PostView>, postId) : data));
}

/** The answer of the API for a post: replaces what the lists hold. */
export function replacePost(queryClient: QueryClient, post: PostView): void {
  updatePostEverywhere(queryClient, post.id, () => post);
}

/** A new or changed post: the lists that may show it start again from the server. */
export function refreshFeeds(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({ queryKey: [FEED_ROOT] });
}

/**
 * Reacts at once and settles with the server: the counts and the highlighted reaction change in every list immediately,
 * then the answer replaces them; a failure puts the post back as it was.
 */
export function useReact(schoolId: string | undefined) {
  const queryClient = useQueryClient();
  return useCallback(
    async (post: Pick<PostView, 'id' | 'myReaction' | 'reactionCounts'>, next: ReactionType | null) => {
      if (!schoolId) return;
      const before = { counts: post.reactionCounts, mine: post.myReaction };
      updatePostEverywhere(queryClient, post.id, (current) => applyReaction(current, next));
      try {
        const summary = await putReaction(schoolId, post.id, next);
        updatePostEverywhere(queryClient, post.id, (current) => withSummary(current, summary));
      } catch (error) {
        updatePostEverywhere(queryClient, post.id, (current) => withSummary(current, before));
        throw error;
      }
    },
    [queryClient, schoolId],
  );
}
