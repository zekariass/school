/** Pure helpers over cursor-paged lists (TanStack's `InfiniteData` shape), for the feeds, the queue and my posts. */

export interface Page<T> {
  items: T[];
  nextCursor?: string | undefined;
}

export interface Pages<T> {
  pages: Page<T>[];
  pageParams: unknown[];
}

/**
 * The items of all loaded pages in order, each id once. A pinned or unpinned post can slip onto two pages between
 * requests (the cursor is not a snapshot): the first copy wins, which is the one nearest the top.
 */
export function flattenPages<T extends { id: string }>(data: Pages<T> | undefined): T[] {
  if (!data) return [];
  const seen = new Set<string>();
  const out: T[] = [];
  for (const page of data.pages) {
    for (const item of page.items) {
      if (seen.has(item.id)) continue;
      seen.add(item.id);
      out.push(item);
    }
  }
  return out;
}

/** Replaces the item `id` in every page by `change(item)`; the same object when nothing matched. */
export function mapItem<T extends { id: string }>(data: Pages<T>, id: string, change: (item: T) => T): Pages<T> {
  let touched = false;
  const pages = data.pages.map((page) => {
    if (!page.items.some((item) => item.id === id)) return page;
    touched = true;
    return { ...page, items: page.items.map((item) => (item.id === id ? change(item) : item)) };
  });
  return touched ? { ...data, pages } : data;
}

/** Drops the item `id` from every page. */
export function removeItem<T extends { id: string }>(data: Pages<T>, id: string): Pages<T> {
  if (!data.pages.some((page) => page.items.some((item) => item.id === id))) return data;
  return { ...data, pages: data.pages.map((page) => ({ ...page, items: page.items.filter((item) => item.id !== id) })) };
}

/** Puts `item` at the head of the first page (a new post you just made), unless it is already there. */
export function prependItem<T extends { id: string }>(data: Pages<T>, item: T): Pages<T> {
  if (data.pages.length === 0) return { ...data, pages: [{ items: [item] }], pageParams: [undefined] };
  if (data.pages.some((page) => page.items.some((existing) => existing.id === item.id))) return data;
  const [first, ...rest] = data.pages;
  return { ...data, pages: [{ ...first!, items: [item, ...first!.items] }, ...rest] };
}
