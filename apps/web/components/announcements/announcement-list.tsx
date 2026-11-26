"use client";

import { useInfiniteQuery, type QueryKey } from "@tanstack/react-query";
import { useMemo } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import type { AnnouncementCard } from "@/lib/announcements/queries";
import { errorMessage } from "@/lib/console/errors";
import { mergePages, nextCursorOf, type PageLike } from "@/lib/feed/pages";
import { AnnouncementListItem } from "./announcement-card";

/** A keyset-paged list of announcement cards ("Load more"). The cards live in memory only. */
export function AnnouncementList({
  queryKey,
  fetchPage,
  hrefFor,
  mode,
  empty,
  failed,
  showSchool,
}: {
  queryKey: QueryKey;
  fetchPage: (cursor?: string) => Promise<PageLike<AnnouncementCard>>;
  hrefFor: (card: AnnouncementCard) => string;
  mode: "reader" | "author";
  empty: string;
  failed: string;
  showSchool?: boolean;
}) {
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => nextCursorOf(last),
    staleTime: 15_000,
    retry: false,
  });
  const items = useMemo(() => mergePages(query.data?.pages), [query.data]);

  if (query.isPending) return <LoadingRows rows={4} />;
  if (query.isError) return <ErrorNotice message={errorMessage(query.error, failed)} onRetry={() => void query.refetch()} />;
  if (items.length === 0) return <EmptyNotice>{empty}</EmptyNotice>;
  return (
    <div className="flex flex-col gap-3">
      <ul className="flex flex-col gap-3" data-testid="announcement-list">
        {items.map((card) => (
          <AnnouncementListItem key={card.id} card={card} href={hrefFor(card)} mode={mode} showSchool={showSchool} />
        ))}
      </ul>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </div>
  );
}
