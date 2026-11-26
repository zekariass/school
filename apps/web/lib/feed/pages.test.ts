import { describe, expect, it } from "vitest";
import { mergePages, nextCursorOf, removeFromPages, replaceInPages } from "./pages";

const item = (id: string, extra: Record<string, unknown> = {}) => ({ id, ...extra });

describe("mergePages", () => {
  it("concatenates pages in order", () => {
    expect(mergePages([{ items: [item("a"), item("b")] }, { items: [item("c")] }]).map((i) => i.id)).toEqual(["a", "b", "c"]);
  });

  it("keeps a post that a pin change repeated on the next page only once, where it was first seen", () => {
    const merged = mergePages([{ items: [item("p1"), item("a"), item("b")], nextCursor: "x" }, { items: [item("b"), item("c")] }]);
    expect(merged.map((i) => i.id)).toEqual(["p1", "a", "b", "c"]);
  });

  it("copes with no pages", () => {
    expect(mergePages(undefined)).toEqual([]);
    expect(mergePages([])).toEqual([]);
  });
});

describe("nextCursorOf", () => {
  it("is the opaque cursor of the last page, undefined at the end", () => {
    expect(nextCursorOf({ items: [], nextCursor: "abc" })).toBe("abc");
    expect(nextCursorOf({ items: [] })).toBeUndefined();
    expect(nextCursorOf({ items: [], nextCursor: "" })).toBeUndefined();
    expect(nextCursorOf(undefined)).toBeUndefined();
  });
});

describe("replaceInPages and removeFromPages", () => {
  const data = { pages: [{ items: [item("a", { n: 1 }), item("b")] }, { items: [item("c")] }], pageParams: [undefined, "x"] };

  it("replaces an item in whichever page holds it", () => {
    const next = replaceInPages(data, item("c", { n: 9 }));
    expect(next?.pages[1]?.items).toEqual([{ id: "c", n: 9 }]);
    expect(next?.pageParams).toEqual(data.pageParams);
  });

  it("removes an item and keeps the page structure", () => {
    const next = removeFromPages(data, "a");
    expect(next?.pages.map((p) => p.items.map((i) => i.id))).toEqual([["b"], ["c"]]);
    expect(replaceInPages(undefined, item("a"))).toBeUndefined();
  });
});
