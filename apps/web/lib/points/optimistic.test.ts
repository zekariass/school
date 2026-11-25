import { describe, expect, it } from "vitest";
import { applyAwardToOverview, awardDelta, awardTargets, totalsOf } from "./optimistic";

const overview = () => ({
  total: 5,
  positiveTotal: 7,
  needsWorkTotal: -2,
  students: [
    { studentId: "a", total: 3, positiveTotal: 4, needsWorkTotal: -1 },
    { studentId: "b", total: 2, positiveTotal: 3, needsWorkTotal: -1 },
    { studentId: "c" },
  ],
});

describe("awardDelta", () => {
  it("is negative for needs-work", () => {
    expect(awardDelta("POSITIVE", 2)).toBe(2);
    expect(awardDelta("NEEDS_WORK", 2)).toBe(-2);
  });
});

describe("applyAwardToOverview", () => {
  it("adds a positive award to the chosen students and the class", () => {
    const next = applyAwardToOverview(overview(), ["a", "c"], 2);
    expect(totalsOf(next.students)).toEqual({ a: 5, b: 2, c: 2 });
    expect(next.students[0]).toMatchObject({ positiveTotal: 6, needsWorkTotal: -1 });
    expect(next.total).toBe(9);
    expect(next.positiveTotal).toBe(11);
    expect(next.needsWorkTotal).toBe(-2);
  });

  it("lowers totals for needs-work and for an undo (negated delta)", () => {
    const awarded = applyAwardToOverview(overview(), ["b"], -1);
    expect(totalsOf(awarded.students).b).toBe(1);
    expect(awarded.students[1]).toMatchObject({ needsWorkTotal: -2 });
    const undone = applyAwardToOverview(awarded, ["b"], 1);
    expect(totalsOf(undone.students).b).toBe(2);
  });

  it("leaves the input and other students alone", () => {
    const before = overview();
    const next = applyAwardToOverview(before, ["a"], 1);
    expect(before.students[0]!.total).toBe(3);
    expect(next.students[1]).toBe(before.students[1]);
  });

  it("ignores ids that are not in the class", () => {
    const next = applyAwardToOverview(overview(), ["zzz"], 3);
    expect(next.total).toBe(5);
  });
});

describe("awardTargets", () => {
  const roster = [{ studentId: "a" }, { studentId: "b" }];
  it("is everybody for a whole-class award", () => {
    expect(awardTargets({ wholeClass: true }, roster)).toEqual(["a", "b"]);
  });
  it("is the chosen ones otherwise", () => {
    expect(awardTargets({ studentIds: ["b"] }, roster)).toEqual(["b"]);
  });
});
