"use client";

import { Client } from "@stomp/stompjs";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useEffect, useRef } from "react";
import { toast } from "sonner";
import { ank } from "@/lib/announcements/queries";
import { isAnnouncementNotification } from "@/lib/announcements/view";
import { getBrowserApi } from "@/lib/api-client";
import { unwrap } from "@/lib/api-error";
import { reconnectDelay } from "@/lib/notifications/backoff";
import { notificationKeys } from "@/lib/notifications/keys";
import { applyRealtimeEvent } from "@/lib/notifications/realtime-cache";
import { resolveRealtimeUrl } from "@/lib/notifications/realtime-url";
import { parseRealtimeEvent, type NotificationItem } from "@/lib/notifications/types";
import { useNotificationActions } from "./use-notification-actions";

const QUEUE = "/user/queue/notifications";
/** A connection that lasted this long counts as healthy: the backoff starts over. */
const STABLE_AFTER_MS = 30_000;
/** A tab hidden this long lets go of its connection (it reconnects when shown again). */
const HIDDEN_DISCONNECT_MS = 5 * 60_000;

/**
 * Live notifications. Mount once in an authenticated layout. Each connection asks the BFF for a
 * fresh single-use ticket (60 s), so a reconnect never replays a spent one; failures back off
 * with jitter. Events patch the TanStack Query caches (badge, dropdown, inbox) and a new
 * notification also shows a toast. After a reconnect everything is refetched, because
 * events missed while offline are not replayed.
 */
export function useRealtime() {
  const queryClient = useQueryClient();
  const t = useTranslations("notifications");
  const actions = useNotificationActions();
  // read through refs so changing language or router identity never reconnects
  const latest = useRef({ t, open: actions.open });
  useEffect(() => {
    latest.current = { t, open: actions.open };
  });

  useEffect(() => {
    const url = resolveRealtimeUrl(process.env.NEXT_PUBLIC_REALTIME_URL);
    let stopped = false;
    let attempt = 0;
    let client: Client | null = null;
    let connecting = false;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let stableTimer: ReturnType<typeof setTimeout> | undefined;
    let hiddenTimer: ReturnType<typeof setTimeout> | undefined;
    let connectedBefore = false;

    function toastCreated(item: NotificationItem) {
      const { t: tr, open } = latest.current;
      toast(item.title, {
        description: item.body ?? undefined,
        action: { label: tr("bell.open"), onClick: () => void open(item) },
      });
    }

    function scheduleRetry() {
      if (stopped || document.hidden) return;
      clearTimeout(retryTimer);
      retryTimer = setTimeout(() => void connect(), reconnectDelay(attempt));
      attempt += 1;
    }

    async function connect() {
      if (stopped || connecting || client) return;
      connecting = true;
      try {
        let ticket: string;
        try {
          ticket = unwrap(await getBrowserApi().POST("/api/v1/realtime/tickets")).ticket;
        } catch {
          scheduleRetry();
          return;
        }
        if (stopped) return;
        const next = new Client({
          brokerURL: url,
          connectHeaders: { ticket },
          reconnectDelay: 0,
          heartbeatIncoming: 10_000,
          heartbeatOutgoing: 10_000,
          onConnect: () => {
            next.subscribe(QUEUE, (frame) => {
              const event = parseRealtimeEvent(frame.body);
              if (!event) return;
              applyRealtimeEvent(queryClient, event);
              if (event.type === "notification.created") {
                toastCreated(event.notification);
                // A new announcement changes the header badge and the inbox.
                if (isAnnouncementNotification(event.notification.data)) void queryClient.invalidateQueries({ queryKey: ank.all });
              }
            });
            if (connectedBefore) void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
            connectedBefore = true;
            clearTimeout(stableTimer);
            stableTimer = setTimeout(() => (attempt = 0), STABLE_AFTER_MS);
          },
          onWebSocketClose: () => {
            clearTimeout(stableTimer);
            if (client === next) {
              client = null;
              scheduleRetry();
            }
          },
        });
        client = next;
        next.activate();
      } finally {
        connecting = false;
      }
    }

    function disconnect() {
      clearTimeout(retryTimer);
      clearTimeout(stableTimer);
      const current = client;
      client = null;
      if (current) void current.deactivate();
    }

    function onVisibility() {
      if (document.hidden) {
        hiddenTimer = setTimeout(disconnect, HIDDEN_DISCONNECT_MS);
        return;
      }
      clearTimeout(hiddenTimer);
      if (!client) {
        attempt = 0;
        void connect();
      }
    }
    const onOnline = () => {
      if (!client) {
        attempt = 0;
        void connect();
      }
    };

    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("online", onOnline);
    if (!document.hidden) void connect();

    return () => {
      stopped = true;
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("online", onOnline);
      clearTimeout(hiddenTimer);
      disconnect();
    };
  }, [queryClient]);
}
