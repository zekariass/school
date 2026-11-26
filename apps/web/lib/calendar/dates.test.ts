import { describe, expect, it } from "vitest";
import { addDays, compareIso, diffDays, eachDay, fromDisplay, isIsoDate, isoInZone, monthBounds, parseIso, shiftMonth, toDisplay, toIso, weekdayOf, weekOf } from "./dates";

describe("ISO dates", () => {
  it("parses only valid calendar dates", () => {
    expect(parseIso("2026-12-07")).toEqual({ year: 2026, month: 12, day: 7 });
    expect(parseIso("2028-02-29")).toEqual({ year: 2028, month: 2, day: 29 });
    expect(parseIso("2026-02-29")).toBeNull();
    expect(parseIso("2026-13-01")).toBeNull();
    expect(parseIso("2026-4-1")).toBeNull();
    expect(parseIso(null)).toBeNull();
    expect(isIsoDate("2026-12-07T10:00")).toBe(false);
    expect(toIso({ year: 5, month: 3, day: 9 })).toBe("0005-03-09");
  });

  it("adds days across months, years and leap days", () => {
    expect(addDays("2026-12-31", 1)).toBe("2027-01-01");
    expect(addDays("2028-02-28", 1)).toBe("2028-02-29");
    expect(addDays("2026-02-28", 1)).toBe("2026-03-01");
    expect(addDays("2026-03-01", -1)).toBe("2026-02-28");
    expect(diffDays("2026-12-01", "2026-12-25")).toBe(24);
    expect(diffDays("2026-12-25", "2026-12-01")).toBe(-24);
    expect(compareIso("2026-01-02", "2026-01-10")).toBeLessThan(0);
    expect(eachDay("2026-12-30", "2027-01-02")).toEqual(["2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02"]);
  });

  it("knows the weekday (0 = Sunday)", () => {
    expect(weekdayOf("2026-12-07")).toBe(1); // a Monday
    expect(weekdayOf("2026-12-06")).toBe(0);
    expect(weekdayOf("2026-12-12")).toBe(6);
    expect(weekdayOf("2000-01-01")).toBe(6);
  });

  it("builds the week from the chosen first day", () => {
    expect(weekOf("2026-12-09", 1)[0]).toBe("2026-12-07");
    expect(weekOf("2026-12-09", 1)[6]).toBe("2026-12-13");
    expect(weekOf("2026-12-13", 1)[0]).toBe("2026-12-07");
    expect(weekOf("2026-12-13", 0)[0]).toBe("2026-12-13");
    expect(weekOf("2026-12-09", 6)[0]).toBe("2026-12-05");
  });
});

describe("time zones", () => {
  it("gives the date of an instant in the zone asked for, never the machine's", () => {
    // 21:30 UTC is already the next day in Addis Ababa (UTC+3) and still the same day in New York.
    expect(isoInZone("2026-12-07T21:30:00Z", "Africa/Addis_Ababa")).toBe("2026-12-08");
    expect(isoInZone("2026-12-07T21:30:00Z", "America/New_York")).toBe("2026-12-07");
    expect(isoInZone("2026-12-07T21:30:00Z", "UTC")).toBe("2026-12-07");
    expect(isoInZone(new Date("2026-12-07T02:00:00Z"), "America/New_York")).toBe("2026-12-06");
  });
});

describe("display calendars", () => {
  it("shows the same day in the Ethiopian calendar and back", () => {
    expect(toDisplay("2025-09-11", "ethiopian")).toEqual({ year: 2018, month: 1, day: 1 });
    expect(toDisplay("2026-12-07", "ethiopian")).toEqual({ year: 2019, month: 3, day: 28 });
    expect(fromDisplay({ year: 2018, month: 1, day: 1 }, "ethiopian")).toBe("2025-09-11");
    expect(toDisplay("2026-12-07", "gregorian")).toEqual({ year: 2026, month: 12, day: 7 });
  });

  it("finds the month that holds a day, Pagume included", () => {
    expect(monthBounds("2026-12-07", "gregorian")).toEqual({ year: 2026, month: 12, days: 31, first: "2026-12-01", last: "2026-12-31" });
    // Pagume 2017 has 5 days: 6 to 10 September 2025.
    expect(monthBounds("2025-09-08", "ethiopian")).toEqual({ year: 2017, month: 13, days: 5, first: "2025-09-06", last: "2025-09-10" });
    // a leap year's Pagume has 6: 6 to 11 September 2023.
    expect(monthBounds("2023-09-11", "ethiopian")).toEqual({ year: 2015, month: 13, days: 6, first: "2023-09-06", last: "2023-09-11" });
  });

  it("moves by months and clamps to the shorter month", () => {
    expect(shiftMonth("2026-01-31", "gregorian", 1)).toBe("2026-02-28");
    expect(shiftMonth("2028-01-31", "gregorian", 1)).toBe("2028-02-29");
    expect(shiftMonth("2026-12-15", "gregorian", 1)).toBe("2027-01-15");
    expect(shiftMonth("2026-01-15", "gregorian", -1)).toBe("2025-12-15");
    expect(shiftMonth("2026-01-15", "gregorian", -13)).toBe("2024-12-15");
    // Nehase 30, 2017 + 1 month = Pagume (5 days) -> Pagume 5; one more is Meskerem 5, 2018
    const nehase30 = fromDisplay({ year: 2017, month: 12, day: 30 }, "ethiopian");
    expect(toDisplay(shiftMonth(nehase30, "ethiopian", 1), "ethiopian")).toEqual({ year: 2017, month: 13, day: 5 });
    expect(toDisplay(shiftMonth(nehase30, "ethiopian", 2), "ethiopian")).toEqual({ year: 2018, month: 1, day: 30 });
    // in a leap year Pagume keeps day 6
    const leap = fromDisplay({ year: 2015, month: 12, day: 30 }, "ethiopian");
    expect(toDisplay(shiftMonth(leap, "ethiopian", 1), "ethiopian")).toEqual({ year: 2015, month: 13, day: 6 });
    // and backwards across the year boundary: Meskerem -> Pagume of the year before
    const meskerem = fromDisplay({ year: 2018, month: 1, day: 3 }, "ethiopian");
    expect(toDisplay(shiftMonth(meskerem, "ethiopian", -1), "ethiopian")).toEqual({ year: 2017, month: 13, day: 3 });
  });
});
