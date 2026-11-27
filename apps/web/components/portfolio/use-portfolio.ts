"use client";

import { useInfiniteQuery, useQuery, type QueryKey } from "@tanstack/react-query";
import { useMemo } from "react";
import { mergePages, nextCursorOf, type PageLike } from "@/lib/feed/pages";
import { fetchCapabilities, PORTFOLIO_GC_MS, pfk, type PortfolioItem } from "@/lib/portfolio/queries";

/** What the caller may do in a school's portfolios (for buttons; the API stays the authority). */
export function usePortfolioCapabilities(schoolId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: pfk.capabilities(schoolId ?? "none"),
    queryFn: () => fetchCapabilities(schoolId!),
    enabled: enabled && !!schoolId,
    staleTime: 60_000,
    gcTime: PORTFOLIO_GC_MS,
    retry: false,
  });
}

/** A keyset-paged list of items, merged with duplicates dropped. Held in memory for a few minutes only. */
export function usePortfolioPages(queryKey: QueryKey, fetchPage: (cursor?: string) => Promise<PageLike<PortfolioItem>>, enabled = true) {
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
    enabled,
    staleTime: 20_000,
    gcTime: PORTFOLIO_GC_MS,
    retry: false,
  });
  const items = useMemo(() => mergePages(query.data?.pages), [query.data]);
  return { query, items };
}
