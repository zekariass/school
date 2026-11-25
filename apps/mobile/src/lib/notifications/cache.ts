import type { InboxItem, RealtimeEvent } from './events';

/** Shape of the inbox infinite query's data (`useInfiniteQuery`). */
export interface InboxPage {
  items: InboxItem[];
  nextCursor?: string;
}
export interface InboxData {
  pages: InboxPage[];
  pageParams: unknown[];
}

/** Puts a new notification at the top of the first page (no duplicate if it is already listed). */
export function prependItem(data: InboxData | undefined, item: InboxItem): InboxData | undefined {
  if (!data || data.pages.length === 0) return data;
  if (data.pages.some((page) => page.items.some((i) => i.id === item.id))) return data;
  const [first, ...rest] = data.pages;
  return { ...data, pages: [{ ...first!, items: [item, ...first!.items] }, ...rest] };
}

/** Marks the given notifications (or all) read at `readAt`; already-read ones keep their time. */
export function markItemsRead(
  data: InboxData | undefined,
  ids: readonly string[],
  all: boolean,
  readAt: string,
): InboxData | undefined {
  if (!data) return data;
  const wanted = new Set(ids);
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((i) => (!i.readAt && (all || wanted.has(i.id)) ? { ...i, readAt } : i)),
    })),
  };
}

export function removeItem(data: InboxData | undefined, id: string): InboxData | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.filter((i) => i.id !== id) })) };
}

/** Same as the inbox but for the "unread only" list: read items leave it. */
export function dropRead(data: InboxData | undefined, ids: readonly string[], all: boolean): InboxData | undefined {
  if (!data) return data;
  const wanted = new Set(ids);
  return {
    ...data,
    pages: data.pages.map((page) => ({ ...page, items: page.items.filter((i) => !(all || wanted.has(i.id))) })),
  };
}

export interface CacheUpdate {
  inbox: (data: InboxData | undefined) => InboxData | undefined;
  unreadOnly: (data: InboxData | undefined) => InboxData | undefined;
  unreadCount: number;
}

/** What a real-time event does to the "all" list, the "unread only" list and the badge. */
export function reduceEvent(event: RealtimeEvent, now: string): CacheUpdate {
  switch (event.type) {
    case 'notification.created':
      return {
        inbox: (d) => prependItem(d, event.notification),
        unreadOnly: (d) => prependItem(d, event.notification),
        unreadCount: event.unreadCount,
      };
    case 'notification.read':
      return {
        inbox: (d) => markItemsRead(d, event.ids, event.all, now),
        unreadOnly: (d) => dropRead(d, event.ids, event.all),
        unreadCount: event.unreadCount,
      };
    case 'notification.archived':
      return {
        inbox: (d) => removeItem(d, event.id),
        unreadOnly: (d) => removeItem(d, event.id),
        unreadCount: event.unreadCount,
      };
  }
}

/** The badge text: nothing for zero, "99+" past 99. */
export function badgeLabel(count: number | undefined): string | null {
  if (!count || count <= 0) return null;
  return count > 99 ? '99+' : String(count);
}
