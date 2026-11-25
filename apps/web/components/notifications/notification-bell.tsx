"use client";

import { useQuery } from "@tanstack/react-query";
import { BellIcon, CheckCheckIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ErrorNotice } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Skeleton } from "@/components/ui/skeleton";
import { Link } from "@/i18n/navigation";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { DROPDOWN_LIMIT, notificationKeys } from "@/lib/notifications/keys";
import type { NotificationPage } from "@/lib/notifications/types";
import { NotificationContent } from "./notification-item";
import { useNotificationActions } from "./use-notification-actions";

export async function fetchUnreadCount(): Promise<{ count: number }> {
  return unwrap(await getBrowserApi().GET("/api/v1/notifications/unread-count"));
}

/** The bell of every top bar: unread badge and the latest notifications. Live through useRealtime. */
export function NotificationBell() {
  const t = useTranslations("notifications");
  const [open, setOpen] = useState(false);
  const actions = useNotificationActions();

  const unread = useQuery({ queryKey: notificationKeys.unreadCount, queryFn: fetchUnreadCount, staleTime: 60_000 });
  const latest = useQuery({
    queryKey: notificationKeys.dropdown,
    queryFn: async (): Promise<NotificationPage> =>
      unwrap(await getBrowserApi().GET("/api/v1/notifications", { params: { query: { limit: DROPDOWN_LIMIT } } })),
    enabled: open,
    staleTime: 30_000,
  });

  const count = unread.data?.count ?? 0;
  const label = count > 0 ? t("bell.labelUnread", { count }) : t("bell.label");

  async function markAll() {
    try {
      await actions.markAllRead();
    } catch {
      toast.error(t("page.actionFailed"));
    }
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="relative size-11 rounded-xl" aria-label={label}>
          <BellIcon className="size-5" aria-hidden />
          {count > 0 ? (
            <span
              data-testid="notification-badge"
              className="absolute end-1 top-1 grid min-w-5 place-items-center rounded-full bg-destructive px-1 text-[0.7rem] font-bold leading-5 text-white"
            >
              {count > 99 ? "99+" : count}
            </span>
          ) : null}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[min(24rem,calc(100vw-1.5rem))] rounded-xl p-1.5">
        <div className="flex items-center justify-between gap-2 px-2 py-1">
          <DropdownMenuLabel className="p-0 text-base">{t("title")}</DropdownMenuLabel>
          <Button variant="ghost" size="sm" className="h-8 gap-1.5" onClick={() => void markAll()} disabled={count === 0}>
            <CheckCheckIcon className="size-4" aria-hidden />
            {t("bell.markAllRead")}
          </Button>
        </div>
        <DropdownMenuSeparator />
        <div className="max-h-96 overflow-y-auto">
          {latest.isPending ? (
            <div className="flex flex-col gap-2 p-2" aria-hidden>
              <Skeleton className="h-14 rounded-lg" />
              <Skeleton className="h-14 rounded-lg" />
            </div>
          ) : latest.isError ? (
            <div className="p-2">
              <ErrorNotice message={t("bell.loadFailed")} onRetry={() => void latest.refetch()} />
            </div>
          ) : latest.data.items.length === 0 ? (
            <p className="p-6 text-center text-sm text-muted-foreground">{t("bell.empty")}</p>
          ) : (
            latest.data.items.map((item) => (
              <DropdownMenuItem
                key={item.id}
                className="cursor-pointer rounded-lg p-2"
                onSelect={() => void actions.open(item)}
              >
                <NotificationContent item={item} className="w-full" />
              </DropdownMenuItem>
            ))
          )}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild className="min-h-10 justify-center rounded-lg font-medium">
          <Link href="/notifications">{t("bell.viewAll")}</Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
