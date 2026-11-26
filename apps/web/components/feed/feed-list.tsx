"use client";

import { type QueryKey } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { errorMessage } from "@/lib/console/errors";
import type { PageLike } from "@/lib/feed/pages";
import type { Post } from "@/lib/feed/queries";
import { PostComposer } from "./composer";
import { PostCard } from "./post-card";
import { useFeedPages } from "./use-feed";

/**
 * A feed of post cards with "Load more" (keyset cursors). Editing opens the composer with the post's own school
 * and class. New content is announced to screen readers politely, not by moving focus.
 */
export function FeedList({
  queryKey,
  fetchPage,
  empty,
  enabled = true,
  canEdit = true,
}: {
  queryKey: QueryKey;
  fetchPage: (cursor?: string) => Promise<PageLike<Post>>;
  empty: React.ReactNode;
  enabled?: boolean;
  canEdit?: boolean;
}) {
  const t = useTranslations("school.feed.list");
  const { query, posts } = useFeedPages(queryKey, fetchPage, enabled);
  const [editing, setEditing] = useState<Post | null>(null);

  if (query.isPending) return <LoadingRows rows={3} />;
  if (query.isError) return <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />;

  return (
    <div className="flex flex-col gap-4">
      <p className="sr-only" role="status" aria-live="polite">
        {t("shown", { count: posts.length })}
      </p>
      {posts.length === 0 ? (
        <EmptyNotice>{empty}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-4">
          {posts.map((post) => (
            <li key={post.id}>
              <PostCard post={post} onEdit={canEdit ? setEditing : undefined} />
            </li>
          ))}
        </ul>
      )}
      <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
      {editing ? <PostComposer schoolId={editing.schoolId} classId={editing.classId ?? undefined} post={editing} onClose={() => setEditing(null)} /> : null}
    </div>
  );
}
