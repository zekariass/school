import { describe, expect, it } from 'vitest';

import { message, page } from './fixtures';
import { clearUnread, compareNewestFirst, flattenMessages, isCurrent, mapMessage, mergeLatest, upsertMessage, type MessageData } from './pages';

const at = (minute: number) => `2026-11-26T10:${String(minute).padStart(2, '0')}:00Z`;
const m = (n: number, over = {}) => message({ id: `m${String(n).padStart(3, '0')}`, sentAt: at(n), ...over });
const data = (...pages: ReturnType<typeof page>[]): MessageData => ({ pages, pageParams: pages.map((_, i) => (i === 0 ? undefined : `cursor-${i}`)) });
const ids = (d: MessageData | undefined) => flattenMessages(d).map((item) => item.id);

describe('ordering', () => {
  it('newest first, ties by id', () => {
    const a = message({ id: 'a', sentAt: at(1) });
    const b = message({ id: 'b', sentAt: at(2) });
    const c = message({ id: 'c', sentAt: at(2) });
    expect([a, b, c].sort(compareNewestFirst).map((x) => x.id)).toEqual(['c', 'b', 'a']);
  });
});

describe('upsertMessage (doorbell)', () => {
  it('puts a new message at the top, in order', () => {
    const next = upsertMessage(data(page([m(5), m(3)], 'more')), m(6));
    expect(ids(next)).toEqual(['m006', 'm005', 'm003']);
    // a message that sorts between two others goes between them
    expect(ids(upsertMessage(data(page([m(5), m(3)])), m(4)))).toEqual(['m005', 'm004', 'm003']);
  });
  it('is idempotent: the same message twice changes nothing', () => {
    const once = upsertMessage(data(page([m(5)])), m(6));
    const twice = upsertMessage(once, m(6));
    expect(ids(twice)).toEqual(['m006', 'm005']);
  });
  it('replaces an edited, deleted or hidden message where it is, on any page', () => {
    const start = data(page([m(5)], 'c1'), page([m(2), m(1)]));
    const tomb = m(2, { status: 'DELETED', body: undefined });
    const next = upsertMessage(start, tomb);
    expect(next.pages[1]?.items[0]?.status).toBe('DELETED');
    expect(next.pages[1]?.items[0]?.body).toBeUndefined();
    expect(ids(next)).toEqual(['m005', 'm002', 'm001']);
  });
  it('starts a list from nothing', () => {
    expect(ids(upsertMessage(undefined, m(1)))).toEqual(['m001']);
    expect(ids(upsertMessage({ pages: [], pageParams: [] }, m(1)))).toEqual(['m001']);
  });
  it('keeps the next-page cursor of the oldest page', () => {
    const next = upsertMessage(data(page([m(5)], 'older')), m(6));
    expect(next.pages[0]?.nextCursor).toBe('older');
  });
});

describe('mapMessage', () => {
  it('changes one message in place', () => {
    const next = mapMessage(data(page([m(2), m(1)])), 'm001', (x) => ({ ...x, reactions: [{ emoji: '👍', count: 1, mine: true }] }));
    expect(next?.pages[0]?.items[1]?.reactions).toHaveLength(1);
    expect(mapMessage(undefined, 'x', (x) => x)).toBeUndefined();
  });
});

describe('mergeLatest (reconnect, polling)', () => {
  it('adds what arrived while away and keeps the older pages', () => {
    const old = data(page([m(5), m(4)], 'c1'), page([m(3), m(2)]));
    const merged = mergeLatest(old, page([m(7), m(6), m(5), m(4)], 'cx'));
    expect(ids(merged)).toEqual(['m007', 'm006', 'm005', 'm004', 'm003', 'm002']);
    expect(merged.pages).toHaveLength(2);
    expect(merged.pages[0]?.nextCursor).toBe('c1');
  });
  it('takes the fresh version of a message that changed (edited, deleted, hidden)', () => {
    const old = data(page([m(5), m(4)]));
    const merged = mergeLatest(old, page([m(5), m(4, { status: 'HIDDEN', body: undefined })]));
    expect(flattenMessages(merged).find((x) => x.id === 'm004')?.status).toBe('HIDDEN');
  });
  it('restarts from the fresh page when more than a page arrived (no gap in the history)', () => {
    const old = data(page([m(5), m(4)], 'c1'), page([m(3)]));
    const merged = mergeLatest(old, page([m(40), m(39)], 'cx'));
    expect(ids(merged)).toEqual(['m040', 'm039']);
    expect(merged.pages).toHaveLength(1);
    expect(merged.pages[0]?.nextCursor).toBe('cx');
  });
  it('starts from nothing', () => {
    expect(ids(mergeLatest(undefined, page([m(1)])))).toEqual(['m001']);
  });
  it('uses the fresh cursor when the fresh page reaches further back than a short cached page', () => {
    const merged = mergeLatest(data(page([m(5)])), page([m(6), m(5), m(4)], 'older'));
    expect(merged.pages[0]?.nextCursor).toBe('older');
  });
});

describe('clearUnread', () => {
  const row = (id: string, unreadCount: number) => ({ id, unreadCount });
  it('sets one conversation to zero and leaves the rest', () => {
    const start = { pages: [{ items: [row('a', 3), row('b', 2)] }], pageParams: [undefined] };
    const next = clearUnread(start, 'a');
    expect(next?.pages[0]?.items).toEqual([row('a', 0), row('b', 2)]);
    expect(clearUnread(next, 'a')).toBe(next); // nothing to change: the same object
    expect(clearUnread(undefined, 'a')).toBeUndefined();
  });
});

describe('isCurrent', () => {
  it('sees whether the cache already holds this version', () => {
    const d = data(page([m(1)]));
    expect(isCurrent(d, m(1))).toBe(true);
    expect(isCurrent(d, m(1, { status: 'DELETED' }))).toBe(false);
    expect(isCurrent(d, m(1, { reactions: [{ emoji: '👍', count: 1, mine: false }] }))).toBe(false);
    expect(isCurrent(d, m(2))).toBe(false);
    expect(isCurrent(undefined, m(1))).toBe(false);
  });
});
