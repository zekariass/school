import { describe, expect, it } from "vitest";
import { dedupePages, mapPages, markItemsRead, prependItem, removeItem } from "./cache";
import type { NotificationItem, NotificationPage } from "./types";

const item = (id: string, readAt?: string): NotificationItem => ({
  id,
  typeCode: "NEW_POST",
  title: `Title ${id}`,
  data: {},
  priority: "NORMAL",
  createdAt: "2026-06-10T10:00:00Z",
  ...(readAt ? { readAt } : {}),
});

const page = (...items: NotificationItem[]): NotificationPage => ({ items });

describe("prependItem", () => {
  it("puts a new item first", () => {
    expect(prependItem(page(item("a")), item("b")).items.map((i) => i.id)).toEqual(["b", "a"]);
  });

  it("ignores an item that is already there (redelivery, refetch races)", () => {
    const p = page(item("a"));
    expect(prependItem(p, item("a"))).toBe(p);
  });

  it("keeps a limited view at its limit", () => {
    const p = page(item("a"), item("b"));
    expect(prependItem(p, item("c"), { limit: 2 }).items.map((i) => i.id)).toEqual(["c", "a"]);
  });

  it("does not add read items to an unread-only view", () => {
    const p = page(item("a"));
    expect(prependItem(p, item("b", "2026-06-10T11:00:00Z"), { unreadOnly: true })).toBe(p);
  });
});

describe("markItemsRead", () => {
  const at = "2026-06-10T12:00:00Z";

  it("marks only the listed items", () => {
    const next = markItemsRead(page(item("a"), item("b")), { ids: ["b"] }, at);
    expect(next.items.map((i) => i.readAt)).toEqual([undefined, at]);
  });

  it("keeps the first read time", () => {
    const first = "2026-06-09T00:00:00Z";
    const p = page(item("a", first));
    expect(markItemsRead(p, { ids: ["a"] }, at)).toBe(p);
  });

  it("marks everything for read-all", () => {
    const next = markItemsRead(page(item("a"), item("b", "2026-06-09T00:00:00Z")), { all: true }, at);
    expect(next.items.map((i) => i.readAt)).toEqual([at, "2026-06-09T00:00:00Z"]);
  });

  it("removes read items from an unread-only view", () => {
    const next = markItemsRead(page(item("a"), item("b")), { ids: ["a"] }, at, { unreadOnly: true });
    expect(next.items.map((i) => i.id)).toEqual(["b"]);
  });

  it("returns the same page when nothing changes", () => {
    const p = page(item("a"));
    expect(markItemsRead(p, { ids: ["zzz"] }, at)).toBe(p);
  });
});

describe("removeItem", () => {
  it("drops an archived item", () => {
    expect(removeItem(page(item("a"), item("b")), "a").items.map((i) => i.id)).toEqual(["b"]);
  });

  it("is a no-op for an unknown id", () => {
    const p = page(item("a"));
    expect(removeItem(p, "x")).toBe(p);
  });
});

describe("infinite lists", () => {
  const data = { pageParams: [undefined, "c1"], pages: [page(item("a")), page(item("b"))] };

  it("mapPages applies to every page and keeps identity when unchanged", () => {
    expect(mapPages(data, (p) => p)).toBe(data);
    const next = mapPages(data, (p) => removeItem(p, "b"));
    expect(next.pages[1]!.items).toEqual([]);
    expect(next.pageParams).toBe(data.pageParams);
  });

  it("dedupePages keeps the first occurrence across pages", () => {
    const dup = { ...data, pages: [page(item("a"), item("b")), page(item("b"), item("c"))] };
    expect(dedupePages(dup).pages.map((p) => p.items.map((i) => i.id))).toEqual([["a", "b"], ["c"]]);
  });
});
