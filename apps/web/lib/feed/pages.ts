/** Keyset-paged lists: cursors are opaque and a pin change between pages can repeat a post, so merging dedupes by id. */

export interface PageLike<T> {
  items: T[];
  nextCursor?: string | null;
}

export interface InfiniteLike<T> {
  pages: PageLike<T>[];
  pageParams: unknown[];
}

/** All items of the loaded pages in order; a post that appears on two pages is kept where it was first seen. */
export function mergePages<T extends { id: string }>(pages: readonly PageLike<T>[] | undefined): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const page of pages ?? []) {
    for (const item of page.items) {
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      out.push(item);
    }
  }
  return out;
}

/** The cursor to ask for the next page, or undefined at the end (`getNextPageParam`). */
export function nextCursorOf<T>(last: PageLike<T> | undefined): string | undefined {
  return last?.nextCursor ? last.nextCursor : undefined;
}

/** Replaces an item (by id) in every loaded page. */
export function replaceInPages<T extends { id: string }>(data: InfiniteLike<T> | undefined, item: T): InfiniteLike<T> | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.map((i) => (i.id === item.id ? item : i)) })) };
}

/** Removes an item (by id) from every loaded page. */
export function removeFromPages<T extends { id: string }>(data: InfiniteLike<T> | undefined, id: string): InfiniteLike<T> | undefined {
  if (!data) return data;
  return { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.filter((i) => i.id !== id) })) };
}
