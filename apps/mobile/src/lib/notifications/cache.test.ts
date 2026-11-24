import { describe, expect, it } from 'vitest';

import { badgeLabel, dropRead, markItemsRead, prependItem, reduceEvent, removeItem, type InboxData } from './cache';
import type { InboxItem } from './events';
import { isPersistableNotificationQuery } from './keys';

const item = (id: string, readAt?: string): InboxItem => ({
  id,
  typeCode: 'NEW_POST',
  title: `t${id}`,
  data: {},
  priority: 'NORMAL',
  createdAt: '2026-11-24T10:00:00Z',
  ...(readAt ? { readAt } : {}),
});
const data = (): InboxData => ({
  pages: [
    { items: [item('3'), item('2', 'earlier')], nextCursor: 'c' },
    { items: [item('1')] },
  ],
  pageParams: [undefined, 'c'],
});
const ids = (d: InboxData | undefined) => d?.pages.flatMap((p) => p.items.map((i) => i.id));

describe('inbox cache', () => {
  it('prepends to the first page, once', () => {
    const next = prependItem(data(), item('4'));
    expect(ids(next)).toEqual(['4', '3', '2', '1']);
    expect(ids(prependItem(next, item('4')))).toEqual(['4', '3', '2', '1']);
  });
  it('leaves an unloaded cache alone', () => {
    expect(prependItem(undefined, item('4'))).toBeUndefined();
    expect(prependItem({ pages: [], pageParams: [] }, item('4'))?.pages).toEqual([]);
  });
  it('marks some or all read without touching earlier read times', () => {
    const some = markItemsRead(data(), ['1'], false, 'now');
    expect(some?.pages.flatMap((p) => p.items.map((i) => i.readAt))).toEqual([undefined, 'earlier', 'now']);
    const all = markItemsRead(data(), [], true, 'now');
    expect(all?.pages.flatMap((p) => p.items.map((i) => i.readAt))).toEqual(['now', 'earlier', 'now']);
  });
  it('removes archived items and drops read ones from the unread list', () => {
    expect(ids(removeItem(data(), '2'))).toEqual(['3', '1']);
    expect(ids(dropRead(data(), ['3'], false))).toEqual(['2', '1']);
    expect(ids(dropRead(data(), [], true))).toEqual([]);
  });
  it('turns events into cache updates and the badge value', () => {
    const created = reduceEvent({ type: 'notification.created', notification: item('9'), unreadCount: 5 }, 'now');
    expect(created.unreadCount).toBe(5);
    expect(ids(created.inbox(data()))).toEqual(['9', '3', '2', '1']);
    const read = reduceEvent({ type: 'notification.read', ids: [], all: true, unreadCount: 0 }, 'now');
    expect(read.unreadCount).toBe(0);
    expect(ids(read.unreadOnly(data()))).toEqual([]);
    const archived = reduceEvent({ type: 'notification.archived', id: '3', unreadCount: 1 }, 'now');
    expect(ids(archived.inbox(data()))).toEqual(['2', '1']);
  });
});

describe('badgeLabel', () => {
  it('hides zero and caps at 99+', () => {
    expect(badgeLabel(0)).toBeNull();
    expect(badgeLabel(undefined)).toBeNull();
    expect(badgeLabel(7)).toBe('7');
    expect(badgeLabel(100)).toBe('99+');
  });
});

describe('persistence', () => {
  it('keeps notification queries out of the persisted cache', () => {
    expect(isPersistableNotificationQuery(['notifications', 'inbox', {}])).toBe(false);
    expect(isPersistableNotificationQuery(['me'])).toBe(true);
  });
});
