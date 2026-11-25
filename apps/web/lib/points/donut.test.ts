import { describe, expect, it } from "vitest";
import { arcDash, donutSlices, type CategoryTotalLike } from "./donut";

const c = (categoryId: string, points: number, polarity: "POSITIVE" | "NEEDS_WORK" = "POSITIVE"): CategoryTotalLike => ({
  categoryId,
  name: categoryId,
  color: "#112233",
  points,
  polarity,
});

describe("donutSlices", () => {
  it("shares positive points, biggest first, slices following each other", () => {
    const slices = donutSlices([c("a", 1), c("b", 3), c("x", -2, "NEEDS_WORK"), c("zero", 0)]);
    expect(slices.map((s) => s.categoryId)).toEqual(["b", "a"]);
    expect(slices[0]).toMatchObject({ share: 0.75, start: 0 });
    expect(slices[1]).toMatchObject({ share: 0.25, start: 0.75 });
    expect(slices.reduce((n, s) => n + s.share, 0)).toBeCloseTo(1);
  });

  it("is empty without positive points", () => {
    expect(donutSlices([c("x", -2, "NEEDS_WORK")])).toEqual([]);
    expect(donutSlices([])).toEqual([]);
  });

  it("falls back for a missing color", () => {
    expect(donutSlices([{ ...c("a", 1), color: null }])[0]!.color).toBe("#2F80ED");
  });
});

describe("arcDash", () => {
  it("offsets each slice by its start", () => {
    const dash = arcDash({ share: 0.25, start: 0.5 }, 100);
    expect(dash.dashoffset).toBe(-50);
    expect(dash.dasharray).toBe("23.5 76.5");
  });
});
