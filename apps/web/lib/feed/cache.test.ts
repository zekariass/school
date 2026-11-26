import { describe, expect, it } from "vitest";
import { dropById, patchById } from "./cache";

const bump = (item: { id: string; n: number }) => ({ ...item, n: item.n + 1 });

describe("patchById", () => {
  it("patches an item inside the pages of an infinite query", () => {
    const data = { pages: [{ items: [{ id: "a", n: 1 }] }, { items: [{ id: "b", n: 1 }, { id: "c", n: 1 }] }], pageParams: [] };
    const next = patchById<{ id: string; n: number }>(data, "b", bump) as typeof data;
    expect(next.pages[1]?.items).toEqual([{ id: "b", n: 2 }, { id: "c", n: 1 }]);
    expect(next.pages[0]).toBe(data.pages[0]);
  });

  it("patches a plain list and a single item", () => {
    expect(patchById<{ id: string; n: number }>([{ id: "a", n: 1 }], "a", bump)).toEqual([{ id: "a", n: 2 }]);
    expect(patchById<{ id: string; n: number }>({ id: "a", n: 1 }, "a", bump)).toEqual({ id: "a", n: 2 });
  });

  it("returns the same object when nothing matches, and ignores unrelated cache entries", () => {
    const data = { pages: [{ items: [{ id: "a", n: 1 }] }], pageParams: [] };
    expect(patchById(data, "zzz", bump)).toBe(data);
    const capabilities = { canPost: true };
    expect(patchById(capabilities, "a", bump)).toBe(capabilities);
    expect(patchById(undefined, "a", bump)).toBeUndefined();
    expect(patchById("text", "a", bump)).toBe("text");
  });
});

describe("dropById", () => {
  it("removes an item from pages and lists", () => {
    const data = { pages: [{ items: [{ id: "a" }, { id: "b" }] }], pageParams: [] };
    expect((dropById(data, "a") as typeof data).pages[0]?.items).toEqual([{ id: "b" }]);
    expect(dropById([{ id: "a" }, { id: "b" }], "b")).toEqual([{ id: "a" }]);
    expect(dropById(data, "zzz")).toBe(data);
  });
});
