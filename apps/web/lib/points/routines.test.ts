import { describe, expect, it } from "vitest";
import { draftsFrom, itemBodies, moveDraft, progress, totalMinutes, visibilityChoices, type ItemDraft } from "./routines";

const d = (text: string, minutes = ""): ItemDraft => ({ key: text, text, minutes });

describe("itemBodies", () => {
  it("drops blank rows and trims text", () => {
    expect(itemBodies([d("  Sit down  "), d("   "), d("Open books")])).toEqual([{ text: "Sit down" }, { text: "Open books" }]);
  });

  it("keeps sensible minutes and ignores the rest", () => {
    expect(itemBodies([d("a", "5"), d("b", "0"), d("c", "-3"), d("d", "x"), d("e", "9999"), d("f", " 2.4 ")])).toEqual([
      { text: "a", minutes: 5 },
      { text: "b" },
      { text: "c" },
      { text: "d" },
      { text: "e", minutes: 600 },
      { text: "f", minutes: 2 },
    ]);
  });

  it("cuts at the API's limits", () => {
    const many = Array.from({ length: 120 }, (_, i) => d(`item ${i}`));
    expect(itemBodies(many)).toHaveLength(100);
    expect(itemBodies([d("x".repeat(500))])[0]!.text).toHaveLength(300);
  });
});

describe("draftsFrom and moveDraft", () => {
  it("makes editable drafts", () => {
    let n = 0;
    expect(draftsFrom([{ text: "a", minutes: 3 }, { text: "b" }], () => `k${++n}`)).toEqual([
      { key: "k1", text: "a", minutes: "3" },
      { key: "k2", text: "b", minutes: "" },
    ]);
  });

  it("moves items and stays put at the ends", () => {
    expect(moveDraft(["a", "b", "c"], 1, -1)).toEqual(["b", "a", "c"]);
    expect(moveDraft(["a", "b", "c"], 1, 1)).toEqual(["a", "c", "b"]);
    expect(moveDraft(["a", "b", "c"], 0, -1)).toEqual(["a", "b", "c"]);
    expect(moveDraft(["a", "b", "c"], 2, 1)).toEqual(["a", "b", "c"]);
  });
});

describe("progress", () => {
  it("counts ticked items inside the list", () => {
    expect(progress(4, new Set([0, 2, 9]))).toEqual({ done: 2, total: 4, ratio: 0.5, complete: false });
    expect(progress(2, new Set([0, 1])).complete).toBe(true);
    expect(progress(0, new Set())).toEqual({ done: 0, total: 0, ratio: 0, complete: false });
  });
});

describe("totals and visibility", () => {
  it("adds up minutes", () => {
    expect(totalMinutes([{ minutes: 5 }, {}, { minutes: 10 }])).toBe(15);
  });

  it("offers school-wide sharing only to points managers", () => {
    expect(visibilityChoices(false)).toEqual(["PRIVATE", "CLASS"]);
    expect(visibilityChoices(true)).toEqual(["PRIVATE", "CLASS", "SCHOOL"]);
  });
});
