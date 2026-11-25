import { describe, expect, it } from "vitest";
import { addDays, daysBetween, defaultRange, isFutureDate, isIsoDate, isToday, localDateIn } from "./dates";

const ADDIS = "Africa/Addis_Ababa"; // UTC+3, no DST
const NEW_YORK = "America/New_York";
const KIRITIMATI = "Pacific/Kiritimati"; // UTC+14
const PAGO_PAGO = "Pacific/Pago_Pago"; // UTC-11

describe("localDateIn", () => {
  it("uses the school's calendar day, not the browser's or UTC's", () => {
    // 22:30 UTC on 24 Nov is already 25 Nov 01:30 in Addis Ababa.
    const instant = new Date("2026-11-24T22:30:00Z");
    expect(localDateIn(ADDIS, instant)).toBe("2026-11-25");
    expect(localDateIn("UTC", instant)).toBe("2026-11-24");
    expect(localDateIn(NEW_YORK, instant)).toBe("2026-11-24");
  });

  it("flips exactly at local midnight", () => {
    expect(localDateIn(ADDIS, new Date("2026-11-24T20:59:59Z"))).toBe("2026-11-24");
    expect(localDateIn(ADDIS, new Date("2026-11-24T21:00:00Z"))).toBe("2026-11-25");
  });

  it("gives different days to the two ends of the world at the same instant", () => {
    const instant = new Date("2026-06-01T12:00:00Z");
    expect(localDateIn(KIRITIMATI, instant)).toBe("2026-06-02");
    expect(localDateIn(PAGO_PAGO, instant)).toBe("2026-06-01");
    expect(localDateIn(KIRITIMATI, new Date("2026-06-01T09:59:59Z"))).toBe("2026-06-01");
  });

  it("follows daylight saving time in the zone that has it", () => {
    // New York leaves DST on 1 Nov 2026 (05:00 UTC): midnight local is 04:00 UTC before, 05:00 UTC after.
    expect(localDateIn(NEW_YORK, new Date("2026-11-01T03:59:59Z"))).toBe("2026-10-31");
    expect(localDateIn(NEW_YORK, new Date("2026-11-01T04:00:00Z"))).toBe("2026-11-01");
    expect(localDateIn(NEW_YORK, new Date("2026-11-02T04:59:59Z"))).toBe("2026-11-01");
    expect(localDateIn(NEW_YORK, new Date("2026-11-02T05:00:00Z"))).toBe("2026-11-02");
  });

  it("falls back to UTC for an unknown zone", () => {
    expect(localDateIn("Not/AZone", new Date("2026-11-24T22:30:00Z"))).toBe("2026-11-24");
  });
});

describe("date arithmetic", () => {
  it("moves across month, year and leap days", () => {
    expect(addDays("2026-11-30", 1)).toBe("2026-12-01");
    expect(addDays("2026-01-01", -1)).toBe("2025-12-31");
    expect(addDays("2028-02-28", 1)).toBe("2028-02-29");
    expect(addDays("2027-02-28", 1)).toBe("2027-03-01");
    expect(addDays("not-a-date", 3)).toBe("not-a-date");
  });

  it("counts days between dates", () => {
    expect(daysBetween("2026-11-01", "2026-11-25")).toBe(24);
    expect(daysBetween("2026-11-25", "2026-11-01")).toBe(-24);
    expect(daysBetween("2026-03-01", "2026-03-01")).toBe(0);
  });

  it("validates real calendar dates", () => {
    expect(isIsoDate("2026-02-28")).toBe(true);
    expect(isIsoDate("2026-02-30")).toBe(false);
    expect(isIsoDate("2026-2-3")).toBe(false);
    expect(isIsoDate("")).toBe(false);
    expect(isIsoDate(undefined)).toBe(false);
  });
});

describe("school-local today", () => {
  const now = new Date("2026-11-24T22:30:00Z");

  it("knows which dates have not started yet", () => {
    expect(isFutureDate("2026-11-25", ADDIS, now)).toBe(false); // already the 25th in Addis Ababa
    expect(isFutureDate("2026-11-26", ADDIS, now)).toBe(true);
    expect(isFutureDate("2026-11-25", NEW_YORK, now)).toBe(true); // still the 24th in New York
  });

  it("labels today per zone", () => {
    expect(isToday("2026-11-25", ADDIS, now)).toBe(true);
    expect(isToday("2026-11-25", "UTC", now)).toBe(false);
  });

  it("builds the default report range ending today", () => {
    expect(defaultRange(ADDIS, now, 30)).toEqual({ from: "2026-10-27", to: "2026-11-25" });
    expect(defaultRange(ADDIS, now, 1)).toEqual({ from: "2026-11-25", to: "2026-11-25" });
  });
});
