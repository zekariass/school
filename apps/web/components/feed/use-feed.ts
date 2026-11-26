"use client";

import { useInfiniteQuery, useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import { fetchCapabilities, fk, type Post } from "@/lib/feed/queries";
import { mergePages, nextCursorOf, type PageLike } from "@/lib/feed/pages";

/** What the caller may do in the feed of a school or class (for buttons; the API stays the authority). */
export function useCapabilities(schoolId: string, classId?: string, enabled = true) {
  return useQuery({
    queryKey: fk.capabilities(schoolId, classId),
    queryFn: () => fetchCapabilities(schoolId, classId),
    enabled,
    staleTime: 60_000,
    retry: false,
  });
}

/** A keyset-paged list of posts: pages are merged with duplicates dropped (a pin change can repeat a post). */
export function useFeedPages(queryKey: QueryKey, fetchPage: (cursor?: string) => Promise<PageLike<Post>>, enabled = true) {
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
    enabled,
    staleTime: 30_000,
  });
  const posts = useMemo(() => mergePages(query.data?.pages), [query.data]);
  return { query, posts };
}

/** Refetch every feed list (after a post is made, moved or removed). */
export function useRefreshFeed() {
  const queryClient = useQueryClient();
  return useCallback(() => queryClient.invalidateQueries({ queryKey: fk.all }), [queryClient]);
}
