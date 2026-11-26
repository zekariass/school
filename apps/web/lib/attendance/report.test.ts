import { describe, expect, it } from "vitest";
import { MAX_REPORT_DAYS, csvFileName, dashboardQuery, dashboardState, isValidRange, reportQuery, reportQueryString, sumReport, visibleDashboardRows } from "./report";

describe("report filters", () => {
  it("leaves empty and malformed filters out", () => {
    expect(reportQuery({ classId: "", studentId: "", from: "", to: "" })).toEqual({});
    expect(reportQuery({ classId: "c1", studentId: "s1", from: "2026-11-01", to: "2026-11-30" })).toEqual({
      classId: "c1",
      studentId: "s1",
      from: "2026-11-01",
      to: "2026-11-30",
    });
    expect(reportQuery({ from: "2026-02-30", to: "yesterday" })).toEqual({});
  });

  it("builds the query string of the CSV call", () => {
    expect(reportQueryString({})).toBe("");
    expect(reportQueryString({ classId: "c1", from: "2026-11-01" })).toBe("?classId=c1&from=2026-11-01");
  });

  it("checks the range: ordered, real dates, at most 400 days", () => {
    expect(isValidRange(undefined, undefined)).toBe(true);
    expect(isValidRange("2026-11-01", undefined)).toBe(true);
    expect(isValidRange("2026-11-01", "2026-11-01")).toBe(true);
    expect(isValidRange("2026-11-02", "2026-11-01")).toBe(false);
    expect(isValidRange("2026-02-30", "2026-03-05")).toBe(false);
    expect(MAX_REPORT_DAYS).toBe(400);
    expect(isValidRange("2025-10-21", "2026-11-25")).toBe(true); // 400 days apart
    expect(isValidRange("2025-10-20", "2026-11-25")).toBe(false); // 401
    expect(isValidRange("2026-01-01", "2026-12-31")).toBe(true);
  });

  it("names the CSV after its range", () => {
    expect(csvFileName({})).toBe("attendance-report.csv");
    expect(csvFileName({ from: "2026-11-01", to: "2026-11-30" })).toBe("attendance-report-2026-11-01_2026-11-30.csv");
    expect(csvFileName({ from: "2026-11-01" })).toBe("attendance-report-2026-11-01.csv");
  });

  it("sums the counters of the rows on screen", () => {
    expect(sumReport([{ marked: 3, absent: 1, present: 2 }, { marked: 2, late: 2 }, {}])).toEqual({
      marked: 5,
      present: 2,
      absent: 1,
      late: 2,
      excused: 0,
      leftEarly: 0,
    });
  });
});

describe("dashboard", () => {
  const rows = [
    { classId: "a", taken: true, students: 20 },
    { classId: "b", taken: false, students: 18 },
    { classId: "c", taken: false, students: 0 },
  ];

  it("tells taken, not taken and empty classes apart", () => {
    expect(rows.map(dashboardState)).toEqual(["taken", "notTaken", "empty"]);
  });

  it("filters to the classes still waiting", () => {
    expect(visibleDashboardRows(rows, true).map((r) => r.classId)).toEqual(["b"]);
    expect(visibleDashboardRows(rows, false).map((r) => r.classId)).toEqual(["a", "b", "c"]);
  });

  it("asks for the school's today when no date is chosen", () => {
    expect(dashboardQuery(null, null)).toEqual({});
    expect(dashboardQuery("2026-11-24", "AM")).toEqual({ date: "2026-11-24", slot: "AM" });
    expect(dashboardQuery("garbage", "")).toEqual({});
  });
});
