"use client";

import type { QueryKey } from "@tanstack/react-query";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { errorMessage } from "@/lib/console/errors";
import type { PageLike } from "@/lib/feed/pages";
import type { PortfolioItem } from "@/lib/portfolio/queries";
import { ItemCard } from "./item-card";
import { usePortfolioPages } from "./use-portfolio";

/**
 * A paged list of item cards from one of the API's list endpoints. `emptyText` is shown for an empty list and
 * `failedText` when the API refuses (the API's own sentence is shown when it sent one).
 */
export function ItemList({
  queryKey,
  fetchPage,
  itemHref,
  showStudent,
  emptyText,
  failedText,
  enabled = true,
  testId,
}: {
  queryKey: QueryKey;
  fetchPage: (cursor?: string) => Promise<PageLike<PortfolioItem>>;
  itemHref: (item: PortfolioItem) => string;
  showStudent?: boolean;
  emptyText: string;
  failedText: string;
  enabled?: boolean;
  testId?: string;
}) {
  const { query, items } = usePortfolioPages(queryKey, fetchPage, enabled);
  if (query.isPending) return enabled ? <LoadingRows rows={3} /> : null;
  if (query.isError) return <ErrorNotice message={errorMessage(query.error, failedText)} onRetry={() => void query.refetch()} />;
  if (items.length === 0) return <EmptyNotice>{emptyText}</EmptyNotice>;
  return (
    <div className="flex flex-col gap-3" data-testid={testId}>
      <ul className="grid gap-3 md:grid-cols-2">
        {items.map((item) => (
          <ItemCard key={item.id} item={item} href={itemHref(item)} showStudent={showStudent} />
        ))}
      </ul>
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </div>
  );
}
