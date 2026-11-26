import { describe, expect, it } from 'vitest';

import { flattenPages, mapItem, prependItem, removeItem, type Pages } from './pages';

interface Item {
  id: string;
  n: number;
}
const data = (...pages: Item[][]): Pages<Item> => ({
  pages: pages.map((items, index) => (index < pages.length - 1 ? { items, nextCursor: `c${index}` } : { items })),
  pageParams: pages.map((_, index) => (index === 0 ? undefined : `c${index - 1}`)),
});

describe('flattenPages', () => {
  it('joins the pages in order', () => {
    expect(flattenPages(data([{ id: 'a', n: 1 }], [{ id: 'b', n: 2 }])).map((item) => item.id)).toEqual(['a', 'b']);
  });
  it('shows a post once when a re-pinned post slips onto two pages', () => {
    const merged = flattenPages(data([{ id: 'a', n: 1 }, { id: 'b', n: 2 }], [{ id: 'b', n: 99 }, { id: 'c', n: 3 }]));
    expect(merged.map((item) => item.id)).toEqual(['a', 'b', 'c']);
    expect(merged[1]?.n).toBe(2);
  });
  it('is empty without data', () => {
    expect(flattenPages(undefined)).toEqual([]);
  });
});

describe('mapItem', () => {
  it('changes only the matching item and keeps untouched pages', () => {
    const before = data([{ id: 'a', n: 1 }], [{ id: 'b', n: 2 }]);
    const after = mapItem(before, 'b', (item) => ({ ...item, n: 5 }));
    expect(after.pages[1]?.items[0]?.n).toBe(5);
    expect(after.pages[0]).toBe(before.pages[0]);
  });
  it('returns the same object when nothing matches', () => {
    const before = data([{ id: 'a', n: 1 }]);
    expect(mapItem(before, 'zzz', (item) => item)).toBe(before);
  });
});

describe('removeItem / prependItem', () => {
  it('removes everywhere', () => {
    const after = removeItem(data([{ id: 'a', n: 1 }], [{ id: 'b', n: 2 }]), 'a');
    expect(flattenPages(after).map((item) => item.id)).toEqual(['b']);
  });
  it('puts a new post first, once', () => {
    const before = data([{ id: 'a', n: 1 }]);
    const after = prependItem(before, { id: 'z', n: 0 });
    expect(flattenPages(after).map((item) => item.id)).toEqual(['z', 'a']);
    expect(prependItem(after, { id: 'z', n: 0 })).toBe(after);
  });
  it('starts a list that had no page', () => {
    expect(flattenPages(prependItem({ pages: [], pageParams: [] }, { id: 'z', n: 0 })).map((item) => item.id)).toEqual(['z']);
  });
});
