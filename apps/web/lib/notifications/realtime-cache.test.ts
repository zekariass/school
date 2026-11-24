import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { notificationKeys } from "./keys";
import { applyRealtimeEvent } from "./realtime-cache";
import type { NotificationItem, NotificationPage } from "./types";

const item = (id: string, readAt?: string): NotificationItem => ({
  id,
  typeCode: "NEW_POST",
  title: id,
  data: {},
  priority: "NORMAL",
  createdAt: "2026-06-10T10:00:00Z",
  ...(readAt ? { readAt } : {}),
});

function seeded() {
  const qc = new QueryClient();
  qc.setQueryData(notificationKeys.unreadCount, { count: 2 });
  qc.setQueryData<NotificationPage>(notificationKeys.dropdown, { items: [item("a"), item("b")] });
  qc.setQueryData(notificationKeys.inbox(false), { pageParams: [undefined], pages: [{ items: [item("a"), item("b")] }] });
  qc.setQueryData(notificationKeys.inbox(true), { pageParams: [undefined], pages: [{ items: [item("a"), item("b")] }] });
  return qc;
}

const ids = (qc: QueryClient, key: readonly unknown[]) => {
  const data = qc.getQueryData<NotificationPage | { pages: NotificationPage[] }>(key)!;
  const items = "pages" in data ? data.pages.flatMap((p) => p.items) : data.items;
  return items.map((i) => i.id);
};

describe("applyRealtimeEvent", () => {
  it("prepends a created item to every view and sets the badge", () => {
    const qc = seeded();
    const event = { type: "notification.created", notification: item("c"), unreadCount: 3 } as const;
    applyRealtimeEvent(qc, event);
    applyRealtimeEvent(qc, event); // delivered twice
    expect(ids(qc, notificationKeys.dropdown)).toEqual(["c", "a", "b"]);
    expect(ids(qc, notificationKeys.inbox(false))).toEqual(["c", "a", "b"]);
    expect(ids(qc, notificationKeys.inbox(true))).toEqual(["c", "a", "b"]);
    expect(qc.getQueryData(notificationKeys.unreadCount)).toEqual({ count: 3 });
  });

  it("does not invent caches that were never loaded", () => {
    const qc = new QueryClient();
    applyRealtimeEvent(qc, { type: "notification.created", notification: item("c"), unreadCount: 1 });
    expect(qc.getQueryData(notificationKeys.dropdown)).toBeUndefined();
    expect(qc.getQueryData(notificationKeys.unreadCount)).toEqual({ count: 1 });
  });

  it("syncs a read elsewhere: marked in the lists, gone from the unread view", () => {
    const qc = seeded();
    applyRealtimeEvent(qc, { type: "notification.read", ids: ["a"], all: false, unreadCount: 1 });
    const dropdown = qc.getQueryData<NotificationPage>(notificationKeys.dropdown)!;
    expect(dropdown.items[0]!.readAt).toBeTruthy();
    expect(dropdown.items[1]!.readAt).toBeUndefined();
    expect(ids(qc, notificationKeys.inbox(true))).toEqual(["b"]);
    expect(qc.getQueryData(notificationKeys.unreadCount)).toEqual({ count: 1 });
  });

  it("read-all empties the unread view and zeroes the badge", () => {
    const qc = seeded();
    applyRealtimeEvent(qc, { type: "notification.read", ids: [], all: true, unreadCount: 0 });
    expect(ids(qc, notificationKeys.inbox(true))).toEqual([]);
    expect(qc.getQueryData<NotificationPage>(notificationKeys.dropdown)!.items.every((i) => i.readAt)).toBe(true);
    expect(qc.getQueryData(notificationKeys.unreadCount)).toEqual({ count: 0 });
  });

  it("removes an archived item everywhere", () => {
    const qc = seeded();
    applyRealtimeEvent(qc, { type: "notification.archived", id: "a", unreadCount: 1 });
    expect(ids(qc, notificationKeys.dropdown)).toEqual(["b"]);
    expect(ids(qc, notificationKeys.inbox(false))).toEqual(["b"]);
    expect(qc.getQueryData(notificationKeys.unreadCount)).toEqual({ count: 1 });
  });
});
