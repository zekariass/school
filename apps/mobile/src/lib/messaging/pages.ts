/**
 * Message list merging. A conversation's pages are cached as TanStack infinite data (`pages` newest first, each page newest first).
 * The realtime doorbell carries ids only: the screen fetches the one message through the API and `upsertMessage` puts it in
 * place; `mergeLatest` folds a fresh first page in after a reconnect or a poll. Pure: no React, no clock.
 */

import type { MessageItem, MessagePage } from './types';

export interface MessageData {
  pages: MessagePage[];
  pageParams: unknown[];
}

/** Newest first: later `sentAt` first; ties by id (ids are time-ordered UUIDs, so the later message has the larger id). */
export function compareNewestFirst(a: Pick<MessageItem, 'id' | 'sentAt'>, b: Pick<MessageItem, 'id' | 'sentAt'>): number {
  const ta = a.sentAt ? Date.parse(a.sentAt) : Number.POSITIVE_INFINITY;
  const tb = b.sentAt ? Date.parse(b.sentAt) : Number.POSITIVE_INFINITY;
  if (ta !== tb) return tb - ta;
  return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
}

/** Every message of the loaded pages, newest first, each id once (the first occurrence wins). */
export function flattenMessages(data: MessageData | undefined): MessageItem[] {
  if (!data) return [];
  const seen = new Set<string>();
  const out: MessageItem[] = [];
  for (const page of data.pages) {
    for (const item of page.items) {
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      out.push(item);
    }
  }
  return out;
}

function insertSorted(items: MessageItem[], message: MessageItem): MessageItem[] {
  const next = [...items];
  const index = next.findIndex((existing) => compareNewestFirst(message, existing) < 0);
  if (index === -1) next.push(message);
  else next.splice(index, 0, message);
  return next;
}

/**
 * Puts one message (fetched after a doorbell, or the answer of a send) into the cache: replaces it wherever it is already (an edit,
 * a deletion, a hide, a reaction), otherwise inserts it into the newest page. Idempotent: the same message twice changes nothing.
 */
export function upsertMessage(data: MessageData | undefined, message: MessageItem): MessageData {
  if (!data || data.pages.length === 0) return { pages: [{ items: [message] }], pageParams: [undefined] };
  let replaced = false;
  const pages = data.pages.map((page) => {
    if (!page.items.some((item) => item.id === message.id)) return page;
    replaced = true;
    return { ...page, items: page.items.map((item) => (item.id === message.id ? message : item)) };
  });
  if (replaced) return { ...data, pages };
  const [first, ...rest] = pages;
  return { ...data, pages: [{ ...first!, items: insertSorted(first!.items, message) }, ...rest] };
}

/** Changes one message in place (a reaction answered by the API, a local edit) without touching the order. No-op when absent. */
export function mapMessage(data: MessageData | undefined, id: string, change: (message: MessageItem) => MessageItem): MessageData | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.map((item) => (item.id === id ? change(item) : item)) })) };
}

/**
 * Folds a freshly fetched newest page into what is cached (after the socket reconnected, or a poll). The fresh page is
 * authoritative for its range. When nothing overlaps, more than a page of messages arrived meanwhile: the old pages would leave a
 * gap, so the cache restarts from the fresh page and older ones load again as the person scrolls.
 */
export function mergeLatest(data: MessageData | undefined, fresh: MessagePage): MessageData {
  if (!data || data.pages.length === 0) return { pages: [fresh], pageParams: [undefined] };
  const [first, ...rest] = data.pages;
  const known = new Set(first!.items.map((item) => item.id));
  const overlaps = fresh.items.some((item) => known.has(item.id)) || fresh.items.length === 0;
  if (!overlaps) return { pages: [fresh], pageParams: [undefined] };
  const freshIds = new Set(fresh.items.map((item) => item.id));
  const oldest = fresh.items[fresh.items.length - 1];
  const kept = first!.items.filter((item) => !freshIds.has(item.id) && (!oldest || compareNewestFirst(item, oldest) > 0));
  const items = [...fresh.items, ...kept].sort(compareNewestFirst);
  const firstOldest = first!.items[first!.items.length - 1];
  // The page's end cursor is that of whichever of the two reaches further back.
  const freshReachesFurther = !firstOldest || (oldest !== undefined && compareNewestFirst(oldest, firstOldest) > 0);
  const nextCursor = freshReachesFurther ? fresh.nextCursor : first!.nextCursor;
  const merged: MessagePage = { items, ...(nextCursor ? { nextCursor } : {}) };
  return { pages: [merged, ...(freshReachesFurther ? [] : rest)], pageParams: freshReachesFurther ? [undefined] : data.pageParams };
}

/** The inbox pages with one conversation's unread count set to 0 (after the person read it: the badge drops before the refetch answers). */
export function clearUnread<T extends { items: { id: string; unreadCount: number }[] }>(
  data: { pages: T[]; pageParams: unknown[] } | undefined,
  conversationId: string,
): typeof data {
  if (!data) return data;
  let touched = false;
  const pages = data.pages.map((page) => {
    if (!page.items.some((item) => item.id === conversationId && item.unreadCount !== 0)) return page;
    touched = true;
    return { ...page, items: page.items.map((item) => (item.id === conversationId ? { ...item, unreadCount: 0 } : item)) };
  });
  return touched ? { ...data, pages } : data;
}

/** Whether the cache already knows this message in this version (same status, edit time and reactions): nothing to do. */
export function isCurrent(data: MessageData | undefined, message: MessageItem): boolean {
  const existing = flattenMessages(data).find((item) => item.id === message.id);
  if (!existing) return false;
  return (
    existing.status === message.status &&
    existing.editedAt === message.editedAt &&
    existing.seenAt === message.seenAt &&
    JSON.stringify(existing.reactions) === JSON.stringify(message.reactions)
  );
}
