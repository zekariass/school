"use client";

import { useInfiniteQuery } from "@tanstack/react-query";
import { ArchiveIcon, CheckCheckIcon, CheckIcon, SettingsIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { dedupePages } from "@/lib/notifications/cache";
import { notificationKeys, PAGE_LIMIT } from "@/lib/notifications/keys";
import type { NotificationPage } from "@/lib/notifications/types";
import { cn } from "@/lib/utils";
import { NotificationContent } from "./notification-item";
import { useNotificationActions } from "./use-notification-actions";

/** The whole inbox: newest first, "Load more", unread filter, archive. */
export function NotificationsView() {
  const t = useTranslations("notifications");
  const [unreadOnly, setUnreadOnly] = useState(false);
  const actions = useNotificationActions();

  const query = useInfiniteQuery({
    queryKey: notificationKeys.inbox(unreadOnly),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }): Promise<NotificationPage> =>
      unwrap(
        await getBrowserApi().GET("/api/v1/notifications", {
          params: { query: { limit: PAGE_LIMIT, unreadOnly, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    getNextPageParam: (last) => last.nextCursor,
    select: dedupePages,
  });

  async function run(action: () => Promise<void>, success?: string) {
    try {
      await action();
      if (success) toast.success(success);
    } catch {
      toast.error(t("page.actionFailed"));
    }
  }

  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const hasUnread = items.some((i) => !i.readAt);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t("title")}</h1>
          <p className="text-muted-foreground">{t("page.subtitle")}</p>
        </div>
        <Button asChild variant="outline" size="sm" className="gap-1.5">
          <Link href="/account/notifications">
            <SettingsIcon className="size-4" aria-hidden />
            {t("page.settings")}
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div role="group" aria-label={t("title")} className="inline-flex rounded-xl border p-1">
          {([false, true] as const).map((value) => (
            <Button
              key={String(value)}
              size="sm"
              variant={unreadOnly === value ? "default" : "ghost"}
              aria-pressed={unreadOnly === value}
              className="h-8 rounded-lg"
              onClick={() => setUnreadOnly(value)}
            >
              {value ? t("page.filterUnread") : t("page.filterAll")}
            </Button>
          ))}
        </div>
        <Button
          variant="outline"
          size="sm"
          className="gap-1.5"
          disabled={!hasUnread}
          onClick={() => void run(actions.markAllRead)}
        >
          <CheckCheckIcon className="size-4" aria-hidden />
          {t("bell.markAllRead")}
        </Button>
      </div>

      {query.isPending ? (
        <LoadingRows rows={5} />
      ) : query.isError ? (
        <ErrorNotice message={t("bell.loadFailed")} onRetry={() => void query.refetch()} />
      ) : items.length === 0 ? (
        <EmptyNotice>{unreadOnly ? t("page.emptyUnread") : t("page.emptyAll")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col divide-y rounded-2xl border bg-card">
          {items.map((item) => (
            <li key={item.id} className={cn("flex items-start gap-2 p-2", !item.readAt && "bg-accent/30")}>
              <button
                type="button"
                className="min-w-0 flex-1 rounded-lg p-2 hover:bg-accent/50 focus-visible:outline-2"
                onClick={() => void actions.open(item)}
              >
                <NotificationContent item={item} />
              </button>
              <div className="flex shrink-0 gap-1 pt-1">
                {!item.readAt ? (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="size-9"
                    aria-label={t("page.markRead")}
                    title={t("page.markRead")}
                    onClick={() => void run(() => actions.markRead(item.id))}
                  >
                    <CheckIcon className="size-4" aria-hidden />
                  </Button>
                ) : null}
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-9"
                  aria-label={t("page.archive")}
                  title={t("page.archive")}
                  onClick={() => void run(() => actions.archive(item.id), t("page.archived"))}
                >
                  <ArchiveIcon className="size-4" aria-hidden />
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {query.hasNextPage ? (
        <div className="flex justify-center">
          <Button variant="outline" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
            {t("page.loadMore")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}
