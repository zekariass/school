import { describe, expect, it } from "vitest";
import { diffDays, toDisplay } from "./dates";
import { fetchWindow, monthGrid, shiftAnchor, visibleRange, weekGrid, MAX_WINDOW_DAYS } from "./grid";

const flat = (weeks: { iso: string }[][]) => weeks.flat().map((c) => c.iso);

describe("monthGrid (Gregorian)", () => {
  it("fills whole weeks from Monday and flags the days of the neighbouring months", () => {
    const grid = monthGrid("2026-12-15", "gregorian", "2026-12-07");
    expect(grid.year).toBe(2026);
    expect(grid.month).toBe(12);
    expect(grid.weeks).toHaveLength(5);
    expect(grid.weeks.every((w) => w.length === 7)).toBe(true);
    expect(grid.weeks[0]![0]!.iso).toBe("2026-11-30");
    expect(grid.weeks[0]![0]!.inMonth).toBe(false);
    expect(grid.weeks[0]![1]!.iso).toBe("2026-12-01");
    expect(grid.weeks[0]![1]!.inMonth).toBe(true);
    expect(grid.weeks[4]![6]!.iso).toBe("2027-01-03");
    expect(grid.weeks[4]![6]!.inMonth).toBe(false);
    expect(grid.weeks.flat().filter((c) => c.inMonth)).toHaveLength(31);
    expect(grid.weeks.flat().filter((c) => c.isToday).map((c) => c.iso)).toEqual(["2026-12-07"]);
    expect(grid.weeks[1]![0]!.weekday).toBe(1);
  });

  it("depends on the first day of the week", () => {
    // February 2026 starts on a Sunday: four exact rows from Sunday, five from Monday (six leading days).
    expect(monthGrid("2026-02-10", "gregorian", "2026-02-10", 0).weeks).toHaveLength(4);
    const monday = monthGrid("2026-02-10", "gregorian", "2026-02-10", 1);
    expect(monday.weeks).toHaveLength(5);
    expect(monday.weeks[0]![0]!.iso).toBe("2026-01-26");
    // the month that needs six rows
    expect(monthGrid("2026-03-10", "gregorian", "2026-03-10", 6).weeks).toHaveLength(5);
    expect(monthGrid("2027-05-10", "gregorian", "2027-05-10", 1).weeks).toHaveLength(6);
  });

  it("handles leap years", () => {
    const feb = monthGrid("2028-02-10", "gregorian", "2028-02-10", 1);
    expect(feb.weeks.flat().filter((c) => c.inMonth)).toHaveLength(29);
    expect(flat(feb.weeks)).toContain("2028-02-29");
    const plain = monthGrid("2027-02-10", "gregorian", "2027-02-10", 1);
    expect(plain.weeks.flat().filter((c) => c.inMonth)).toHaveLength(28);
  });

  it("shows day numbers of the display calendar", () => {
    const grid = monthGrid("2026-12-15", "gregorian", "2026-12-07");
    expect(grid.weeks[1]![0]).toMatchObject({ iso: "2026-12-07", day: 7, month: 12, year: 2026 });
  });
});

describe("monthGrid (Ethiopian)", () => {
  it("shows 30-day months in Ethiopian numbers", () => {
    // 2026-12-07 is Hidar 28, 2019; Hidar 1 is 2026-11-10 (a Tuesday).
    const grid = monthGrid("2026-12-07", "ethiopian", "2026-12-07");
    expect(grid.year).toBe(2019);
    expect(grid.month).toBe(3);
    expect(grid.weeks.flat().filter((c) => c.inMonth)).toHaveLength(30);
    const first = grid.weeks.flat().find((c) => c.inMonth)!;
    expect(first.iso).toBe("2026-11-10");
    expect(first.day).toBe(1);
    expect(grid.weeks[0]![0]!.iso).toBe("2026-11-09");
    expect(grid.weeks.flat().find((c) => c.isToday)).toMatchObject({ iso: "2026-12-07", day: 28, month: 3, year: 2019 });
  });

  it("shows the 13th month, Pagume, of 5 days in two rows", () => {
    const grid = monthGrid("2025-09-08", "ethiopian", "2025-09-08");
    expect(grid.month).toBe(13);
    expect(grid.year).toBe(2017);
    expect(grid.weeks).toHaveLength(2);
    const inMonth = grid.weeks.flat().filter((c) => c.inMonth);
    expect(inMonth.map((c) => c.iso)).toEqual(["2025-09-06", "2025-09-07", "2025-09-08", "2025-09-09", "2025-09-10"]);
    expect(inMonth.map((c) => c.day)).toEqual([1, 2, 3, 4, 5]);
    // the day after is the Ethiopian New Year of 2018, outside the month
    const next = grid.weeks.flat().find((c) => c.iso === "2025-09-11");
    expect(next).toMatchObject({ inMonth: false, day: 1, month: 1, year: 2018 });
  });

  it("shows six Pagume days in a leap year", () => {
    const grid = monthGrid("2023-09-11", "ethiopian", "2023-09-11");
    expect(grid.weeks.flat().filter((c) => c.inMonth)).toHaveLength(6);
    expect(grid.weeks.flat().filter((c) => c.inMonth).at(-1)).toMatchObject({ iso: "2023-09-11", day: 6, month: 13, year: 2015 });
  });

  it("never loses or repeats a day across a whole Ethiopian year", () => {
    let anchor = "2025-09-11"; // 1 Meskerem 2018
    const seen = new Set<string>();
    let months = 0;
    for (let i = 0; i < 13; i++) {
      const grid = monthGrid(anchor, "ethiopian", "2025-09-11");
      for (const c of grid.weeks.flat().filter((x) => x.inMonth)) {
        expect(seen.has(c.iso)).toBe(false);
        seen.add(c.iso);
      }
      months++;
      anchor = shiftAnchor("month", anchor, "ethiopian", 1);
    }
    expect(months).toBe(13);
    expect(seen.size).toBe(365 + 0); // 2018 E.C. is not a leap year
    expect(toDisplay(anchor, "ethiopian")).toMatchObject({ year: 2019, month: 1 });
  });
});

describe("weekGrid and visibleRange", () => {
  it("lists the seven days of a week", () => {
    const week = weekGrid("2026-12-09", "gregorian", "2026-12-09");
    expect(week.map((c) => c.iso)).toEqual(["2026-12-07", "2026-12-08", "2026-12-09", "2026-12-10", "2026-12-11", "2026-12-12", "2026-12-13"]);
    expect(week.filter((c) => c.isToday)).toHaveLength(1);
  });

  it("computes what each view shows", () => {
    expect(visibleRange("week", "2026-12-09", "gregorian")).toEqual({ from: "2026-12-07", to: "2026-12-13" });
    expect(visibleRange("month", "2026-12-09", "gregorian")).toEqual({ from: "2026-11-30", to: "2027-01-03" });
    expect(visibleRange("agenda", "2026-12-09", "gregorian")).toEqual({ from: "2026-12-01", to: "2026-12-31" });
    // Ethiopian months: Hidar 2019 is 10 Nov to 9 Dec 2026
    expect(visibleRange("agenda", "2026-12-07", "ethiopian")).toEqual({ from: "2026-11-10", to: "2026-12-09" });
    expect(visibleRange("month", "2026-12-07", "ethiopian")).toEqual({ from: "2026-11-09", to: "2026-12-13" });
  });

  it("moves a week or a month", () => {
    expect(shiftAnchor("week", "2026-12-09", "gregorian", 1)).toBe("2026-12-16");
    expect(shiftAnchor("week", "2026-12-09", "ethiopian", -1)).toBe("2026-12-02");
    expect(shiftAnchor("month", "2026-12-09", "gregorian", 1)).toBe("2027-01-09");
    expect(shiftAnchor("agenda", "2026-12-09", "gregorian", -1)).toBe("2026-11-09");
    expect(toDisplay(shiftAnchor("month", "2026-12-07", "ethiopian", 1), "ethiopian")).toMatchObject({ month: 4, day: 28 });
  });
});

describe("fetchWindow", () => {
  it("pads the visible range by a day before and two after, at UTC midnights", () => {
    expect(fetchWindow({ from: "2026-11-30", to: "2027-01-03" })).toEqual({ from: "2026-11-29T00:00:00Z", to: "2027-01-05T00:00:00Z" });
  });

  it("covers every day shown, in any time zone, and stays within the API's limit", () => {
    for (const view of ["month", "week", "agenda"] as const) {
      for (const system of ["gregorian", "ethiopian"] as const) {
        const range = visibleRange(view, "2026-12-07", system);
        const window = fetchWindow(range);
        expect(new Date(window.from).getTime()).toBeLessThanOrEqual(Date.parse(`${range.from}T00:00:00+14:00`));
        expect(new Date(window.to).getTime()).toBeGreaterThanOrEqual(Date.parse(`${range.to}T23:59:59-12:00`));
        expect(diffDays(window.from.slice(0, 10), window.to.slice(0, 10))).toBeLessThanOrEqual(MAX_WINDOW_DAYS);
      }
    }
  });

  it("refuses a range longer than a year", () => {
    expect(() => fetchWindow({ from: "2026-01-01", to: "2027-12-31" })).toThrow();
  });
});
