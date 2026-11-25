import { describe, expect, it } from "vitest";
import { csvFileName, isIsoDate, isValidRange, reportQuery, sumPoints } from "./report";

describe("reportQuery", () => {
  it("leaves out empty filters", () => {
    expect(reportQuery({ classId: "", categoryId: undefined, from: "", to: "" })).toEqual({});
  });

  it("keeps the chosen filters and drops malformed dates", () => {
    expect(reportQuery({ classId: "c1", categoryId: "k", from: "2026-09-01", to: "2026-13-45" })).toEqual({
      classId: "c1",
      categoryId: "k",
      from: "2026-09-01",
    });
  });
});

describe("range", () => {
  it("accepts open and ordered ranges", () => {
    expect(isValidRange(undefined, undefined)).toBe(true);
    expect(isValidRange("2026-09-01", undefined)).toBe(true);
    expect(isValidRange("2026-09-01", "2026-09-01")).toBe(true);
    expect(isValidRange("2026-09-01", "2026-10-01")).toBe(true);
  });

  it("rejects reversed and malformed ranges", () => {
    expect(isValidRange("2026-10-01", "2026-09-01")).toBe(false);
    expect(isValidRange("yesterday", undefined)).toBe(false);
    expect(isIsoDate("2026-02-30")).toBe(false);
  });
});

describe("csvFileName and sum", () => {
  it("names the file after the range", () => {
    expect(csvFileName({})).toBe("points-report.csv");
    expect(csvFileName({ from: "2026-09-01", to: "2026-09-30" })).toBe("points-report-2026-09-01_2026-09-30.csv");
  });

  it("sums net points", () => {
    expect(sumPoints([{ points: 3 }, { points: -1 }, {}])).toBe(2);
  });
});
