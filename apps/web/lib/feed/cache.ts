/**
 * Patches a post or story wherever the TanStack cache holds it (feeds, "my posts", the single-post query), so a
 * reaction or a status change shows everywhere at once. Pure over the cached value: `data` may be an infinite
 * query (`{pages: [{items}]}`), a list of items or one item.
 */

type Identified = { id: string };

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null;

/** Applies `fn` to every item with `id` inside `data` and returns the new value (the same object when nothing matched). */
export function patchById<T extends Identified>(data: unknown, id: string, fn: (item: T) => T): unknown {
  if (Array.isArray(data)) {
    let changed = false;
    const next = data.map((entry) => {
      if (isObject(entry) && entry.id === id) {
        changed = true;
        return fn(entry as unknown as T);
      }
      return entry;
    });
    return changed ? next : data;
  }
  if (!isObject(data)) return data;
  if (Array.isArray(data.pages)) {
    let changed = false;
    const pages = (data.pages as unknown[]).map((page) => {
      if (isObject(page) && Array.isArray(page.items)) {
        const items = patchById<T>(page.items, id, fn);
        if (items !== page.items) {
          changed = true;
          return { ...page, items };
        }
      }
      return page;
    });
    return changed ? { ...data, pages } : data;
  }
  if (data.id === id) return fn(data as unknown as T);
  return data;
}

/** Drops the item with `id` from lists, pages or (for a single item) leaves it alone. */
export function dropById(data: unknown, id: string): unknown {
  if (Array.isArray(data)) {
    const next = data.filter((entry) => !(isObject(entry) && entry.id === id));
    return next.length === data.length ? data : next;
  }
  if (isObject(data) && Array.isArray(data.pages)) {
    let changed = false;
    const pages = (data.pages as unknown[]).map((page) => {
      if (isObject(page) && Array.isArray(page.items)) {
        const items = dropById(page.items, id);
        if (items !== page.items) {
          changed = true;
          return { ...page, items };
        }
      }
      return page;
    });
    return changed ? { ...data, pages } : data;
  }
  return data;
}
