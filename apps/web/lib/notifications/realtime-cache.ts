import type { InfiniteData, QueryClient } from "@tanstack/react-query";
import { DROPDOWN_LIMIT, notificationKeys } from "./keys";
import {
  mapPages,
  markItemsRead,
  prependItem,
  removeItem,
} from "./cache";
import type { NotificationPage, RealtimeEvent } from "./types";

type Infinite = InfiniteData<NotificationPage, string | undefined>;

function patchLists(
  queryClient: QueryClient,
  onPage: (page: NotificationPage, options: { unreadOnly: boolean; limit?: number }) => NotificationPage,
  onInfinite: (data: Infinite, unreadOnly: boolean) => Infinite,
) {
  queryClient.setQueryData<NotificationPage>(notificationKeys.dropdown, (page) =>
    page ? onPage(page, { unreadOnly: false, limit: DROPDOWN_LIMIT }) : page,
  );
  for (const unreadOnly of [false, true]) {
    queryClient.setQueryData<Infinite>(notificationKeys.inbox(unreadOnly), (data) =>
      data ? onInfinite(data, unreadOnly) : data,
    );
  }
}

/** The unread badge, set to the authoritative number the server sent. */
export function setUnreadCount(queryClient: QueryClient, count: number) {
  queryClient.setQueryData(notificationKeys.unreadCount, { count });
}

/** Patches every cached view (badge, dropdown, full list) for an event from the gateway. Idempotent. */
export function applyRealtimeEvent(queryClient: QueryClient, event: RealtimeEvent, now: Date = new Date()) {
  applyEventToLists(queryClient, event, now);
  setUnreadCount(queryClient, event.unreadCount);
}

/** Like {@link applyRealtimeEvent} without touching the badge (for the user's own actions, which refetch it). */
export function applyEventToLists(queryClient: QueryClient, event: RealtimeEvent, now: Date = new Date()) {
  const readAt = now.toISOString();
  switch (event.type) {
    case "notification.created":
      patchLists(
        queryClient,
        (page, options) => prependItem(page, event.notification, options),
        (data, unreadOnly) =>
          mapPages(data, (page, index) => (index === 0 ? prependItem(page, event.notification, { unreadOnly }) : page)),
      );
      break;
    case "notification.read":
      patchLists(
        queryClient,
        (page, options) => markItemsRead(page, event, readAt, options),
        (data, unreadOnly) => mapPages(data, (page) => markItemsRead(page, event, readAt, { unreadOnly })),
      );
      break;
    case "notification.archived":
      patchLists(
        queryClient,
        (page) => removeItem(page, event.id),
        (data) => mapPages(data, (page) => removeItem(page, event.id)),
      );
      break;
  }
}
