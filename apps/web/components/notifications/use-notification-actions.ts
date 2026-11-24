"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useCallback } from "react";
import { toast } from "sonner";
import { getBrowserApi } from "@/lib/api-client";
import { ensureOk, unwrap } from "@/lib/api-error";
import { useRouter } from "@/i18n/navigation";
import { notificationKeys } from "@/lib/notifications/keys";
import { resolveDeepLink } from "@/lib/notifications/deep-link";
import { applyEventToLists } from "@/lib/notifications/realtime-cache";
import type { NotificationItem } from "@/lib/notifications/types";

/**
 * What the user can do with notifications. Lists are patched at once (the same reducers the
 * realtime events use) and the badge is refetched; the gateway's echo to other tabs and devices
 * keeps them in step.
 */
export function useNotificationActions() {
  const queryClient = useQueryClient();
  const router = useRouter();
  const t = useTranslations("notifications");

  const refreshBadge = useCallback(
    () => queryClient.invalidateQueries({ queryKey: notificationKeys.unreadCount }),
    [queryClient],
  );

  const markRead = useCallback(
    async (id: string) => {
      ensureOk(await getBrowserApi().POST("/api/v1/notifications/{id}/read", { params: { path: { id } } }));
      applyEventToLists(queryClient, { type: "notification.read", ids: [id], all: false, unreadCount: 0 });
      await refreshBadge();
    },
    [queryClient, refreshBadge],
  );

  const markAllRead = useCallback(async () => {
    unwrap(await getBrowserApi().POST("/api/v1/notifications/read-all"));
    applyEventToLists(queryClient, { type: "notification.read", ids: [], all: true, unreadCount: 0 });
    await refreshBadge();
  }, [queryClient, refreshBadge]);

  const archive = useCallback(
    async (id: string) => {
      ensureOk(await getBrowserApi().POST("/api/v1/notifications/{id}/archive", { params: { path: { id } } }));
      applyEventToLists(queryClient, { type: "notification.archived", id, unreadCount: 0 });
      await refreshBadge();
    },
    [queryClient, refreshBadge],
  );

  /** Marks the item read and goes where it points, when that is a safe in-app path. */
  const open = useCallback(
    async (item: NotificationItem) => {
      const path = resolveDeepLink(item.data);
      if (!item.readAt) {
        try {
          await markRead(item.id);
        } catch {
          toast.error(t("page.actionFailed"));
        }
      }
      if (path) router.push(path);
    },
    [markRead, router, t],
  );

  return { markRead, markAllRead, archive, open };
}
