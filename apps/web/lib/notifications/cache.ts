import type { InfiniteData } from "@tanstack/react-query";
import type { NotificationItem, NotificationPage } from "./types";

/**
 * Pure reducers over the cached inbox pages. The bell's dropdown caches one page, the
 * notifications page an infinite list; realtime events and the user's own actions run
 * the same reducers on both so the two views never disagree.
 */

export interface ViewOptions {
  /** The view lists unread items only: read items leave it. */
  unreadOnly?: boolean;
  /** Keep at most this many items (the dropdown shows the latest few). */
  limit?: number;
}

/** Adds a new item at the top unless it is already there; keeps the list newest-first without repeats. */
export function prependItem(page: NotificationPage, item: NotificationItem, options: ViewOptions = {}): NotificationPage {
  if (page.items.some((i) => i.id === item.id)) return page;
  if (options.unreadOnly && item.readAt) return page;
  const items = [item, ...page.items];
  return { ...page, items: options.limit !== undefined ? items.slice(0, options.limit) : items };
}

/** Marks the given items (or all of them) read; an unread-only view drops them. */
export function markItemsRead(
  page: NotificationPage,
  target: { ids?: readonly string[]; all?: boolean },
  readAt: string,
  options: ViewOptions = {},
): NotificationPage {
  const hit = (item: NotificationItem) => !item.readAt && (target.all === true || !!target.ids?.includes(item.id));
  if (!page.items.some(hit)) return page;
  const items = options.unreadOnly
    ? page.items.filter((item) => !hit(item))
    : page.items.map((item) => (hit(item) ? { ...item, readAt } : item));
  return { ...page, items };
}

/** Removes an archived item. */
export function removeItem(page: NotificationPage, id: string): NotificationPage {
  if (!page.items.some((i) => i.id === id)) return page;
  return { ...page, items: page.items.filter((i) => i.id !== id) };
}

/** Applies a page reducer to every page of an infinite list. Prepending only touches the first page. */
export function mapPages(
  data: InfiniteData<NotificationPage, string | undefined>,
  fn: (page: NotificationPage, index: number) => NotificationPage,
): InfiniteData<NotificationPage, string | undefined> {
  let changed = false;
  const pages = data.pages.map((page, index) => {
    const next = fn(page, index);
    if (next !== page) changed = true;
    return next;
  });
  return changed ? { ...data, pages } : data;
}

/** Keeps items unique by id across pages (a refetch after a prepend can repeat the boundary item). */
export function dedupePages(
  data: InfiniteData<NotificationPage, string | undefined>,
): InfiniteData<NotificationPage, string | undefined> {
  const seen = new Set<string>();
  return {
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.filter((item) => {
        if (seen.has(item.id)) return false;
        seen.add(item.id);
        return true;
      }),
    })),
  };
}
