import { describe, expect, it } from "vitest";
import { formatInstant, isCalendarPreference } from "./format-instant";

// 2025-09-11T09:00Z is 12:00 in Addis Ababa (+03:00) and 1 Meskerem 2018 E.C. (Ethiopian New Year).
const INSTANT = "2025-09-11T09:00:00Z";
const ZONE = "Africa/Addis_Ababa";

describe("formatInstant", () => {
  it("formats Gregorian in the given timezone", () => {
    const out = formatInstant(INSTANT, { locale: "en", calendar: "gregorian", timeZone: ZONE });
    expect(out).toContain("September 11, 2025");
    expect(out).toMatch(/12:00\s?PM/);
  });

  it("formats Ethiopian dates with the time of day", () => {
    const out = formatInstant(INSTANT, { locale: "en", calendar: "ethiopian", timeZone: ZONE });
    expect(out).toMatch(/^Meskerem 1, 2018 E\.C\., 12:00\s?PM$/);
  });

  it("uses Amharic month names for the am locale", () => {
    const out = formatInstant(INSTANT, { locale: "am", calendar: "ethiopian", timeZone: ZONE });
    expect(out).toContain("መስከረም 1 ቀን 2018 ዓ.ም.");
  });

  it("shows the same instant on the clock of the zone it is given", () => {
    // 22:00Z on Sept 10 is already Sept 11 at +03:00 but still Sept 10 in UTC.
    const instant = "2025-09-10T22:00:00Z";
    expect(formatInstant(instant, { locale: "en", calendar: "gregorian", timeZone: ZONE })).toContain("September 11");
    expect(formatInstant(instant, { locale: "en", calendar: "gregorian", timeZone: "UTC" })).toContain("September 10");
    expect(formatInstant(instant, { locale: "en", calendar: "gregorian", timeZone: "Pacific/Auckland" })).toContain("September 11");
  });

  it("does not draw the Ethiopian calendar without a zone", () => {
    const out = formatInstant(INSTANT, { locale: "en", calendar: "ethiopian" });
    expect(out).toContain("2025");
    expect(out).not.toContain("E.C.");
  });

  it("returns an empty string for invalid input", () => {
    expect(formatInstant("not a date", { locale: "en", calendar: "gregorian", timeZone: "UTC" })).toBe("");
  });
});

describe("calendar preference helpers", () => {
  it("validates stored values", () => {
    expect(isCalendarPreference("ethiopian")).toBe(true);
    expect(isCalendarPreference("julian")).toBe(false);
    expect(isCalendarPreference(null)).toBe(false);
  });
});
