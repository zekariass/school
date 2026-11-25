import { describe, expect, it } from "vitest";
import { STATUSES, isStatus, type AttendanceStatus, minutesTextValid, nextStatus, parseMinutes, previousStatus, takesMinutes } from "./status";

describe("status cycling", () => {
  it("goes through all five marks and around", () => {
    let status: AttendanceStatus = STATUSES[0];
    const seen: AttendanceStatus[] = [status];
    for (let i = 0; i < STATUSES.length; i++) {
      status = nextStatus(status);
      seen.push(status);
    }
    expect(seen).toEqual(["PRESENT", "ABSENT", "LATE", "EXCUSED", "LEFT_EARLY", "PRESENT"]);
  });

  it("goes back with the previous mark", () => {
    expect(previousStatus("PRESENT")).toBe("LEFT_EARLY");
    expect(previousStatus("LATE")).toBe("ABSENT");
    for (const s of STATUSES) expect(previousStatus(nextStatus(s))).toBe(s);
  });

  it("recognizes the API's statuses only", () => {
    expect(isStatus("LATE")).toBe(true);
    expect(isStatus("late")).toBe(false);
    expect(isStatus(undefined)).toBe(false);
  });
});

describe("late minutes", () => {
  it("only LATE carries minutes", () => {
    expect(takesMinutes("LATE")).toBe(true);
    expect(takesMinutes("ABSENT")).toBe(false);
  });

  it("accepts whole numbers from 1 to 600", () => {
    expect(parseMinutes("1")).toBe(1);
    expect(parseMinutes(" 45 ")).toBe(45);
    expect(parseMinutes("600")).toBe(600);
    expect(parseMinutes("0")).toBeUndefined();
    expect(parseMinutes("601")).toBeUndefined();
    expect(parseMinutes("4.5")).toBeUndefined();
    expect(parseMinutes("-3")).toBeUndefined();
    expect(parseMinutes("abc")).toBeUndefined();
    expect(parseMinutes("")).toBeUndefined();
  });

  it("treats an empty field as valid and everything unusable as invalid", () => {
    expect(minutesTextValid("")).toBe(true);
    expect(minutesTextValid("  ")).toBe(true);
    expect(minutesTextValid("12")).toBe(true);
    expect(minutesTextValid("0")).toBe(false);
    expect(minutesTextValid("x")).toBe(false);
  });
});
