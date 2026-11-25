import { describe, expect, it } from "vitest";
import {
  awardableCategories,
  categoryColor,
  categoryIcon,
  manageableCategories,
  pointsFor,
  readableOn,
  signedPoints,
  type CategoryLike,
} from "./categories";

const cat = (over: Partial<CategoryLike> & { id: string }): CategoryLike => ({
  name: over.id,
  defaultPoints: 1,
  polarity: "POSITIVE",
  context: "CLASS",
  active: true,
  sortOrder: 0,
  ...over,
});

describe("awardableCategories", () => {
  const all = [
    cat({ id: "work-b", polarity: "NEEDS_WORK", sortOrder: 1 }),
    cat({ id: "kind", sortOrder: 2 }),
    cat({ id: "home", context: "HOME" }),
    cat({ id: "off", active: false }),
    cat({ id: "focus", sortOrder: 1 }),
    cat({ id: "alpha", sortOrder: 2 }),
  ];

  it("puts positive first, then by sort order and name", () => {
    expect(awardableCategories(all, true).map((c) => c.id)).toEqual(["focus", "alpha", "kind", "work-b"]);
  });

  it("drops needs-work ones when the school switched them off", () => {
    expect(awardableCategories(all, false).map((c) => c.id)).toEqual(["focus", "alpha", "kind"]);
  });

  it("never shows home or inactive categories", () => {
    const ids = awardableCategories(all, true).map((c) => c.id);
    expect(ids).not.toContain("home");
    expect(ids).not.toContain("off");
  });

  it("does not change its input", () => {
    const before = all.map((c) => c.id);
    awardableCategories(all, true);
    expect(all.map((c) => c.id)).toEqual(before);
  });
});

describe("manageableCategories", () => {
  it("lists active ones first", () => {
    const list = [cat({ id: "b", active: false }), cat({ id: "a" }), cat({ id: "c", polarity: "NEEDS_WORK" })];
    expect(manageableCategories(list).map((c) => c.id)).toEqual(["a", "c", "b"]);
  });
});

describe("points", () => {
  it("signs by polarity", () => {
    expect(signedPoints("POSITIVE", 3)).toBe(3);
    expect(signedPoints("NEEDS_WORK", 3)).toBe(-3);
    expect(signedPoints("NEEDS_WORK", -3)).toBe(-3);
  });

  it("uses the default or a clamped override", () => {
    expect(pointsFor({ defaultPoints: 2 }, null)).toBe(2);
    expect(pointsFor({ defaultPoints: 2 }, 5)).toBe(5);
    expect(pointsFor({ defaultPoints: 2 }, 99)).toBe(10);
    expect(pointsFor({ defaultPoints: 2 }, 0)).toBe(1);
  });
});

describe("look", () => {
  it("falls back for missing or bad colors and icons", () => {
    expect(categoryColor({ color: "red" })).toBe("#27AE60");
    expect(categoryColor({ color: "#112233" })).toBe("#112233");
    expect(categoryIcon({ icon: "  " })).toBe("⭐");
    expect(categoryIcon({ icon: "🎯" })).toBe("🎯");
  });

  it("picks readable text", () => {
    expect(readableOn("#ffffff")).toBe("#000000");
    expect(readableOn("#000000")).toBe("#ffffff");
  });
});
