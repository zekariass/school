import { describe, expect, it } from "vitest";
import { defaultCalendarFor, formatInstant, isCalendarPreference } from "./format-instant";

// 2025-09-11T09:00Z is 12:00 in Addis Ababa and 1 Meskerem 2018 E.C. (Ethiopian New Year).
const INSTANT = "2025-09-11T09:00:00Z";

describe("formatInstant", () => {
  it("formats Gregorian in the display timezone", () => {
    const out = formatInstant(INSTANT, { locale: "en", calendar: "gregorian" });
    expect(out).toContain("September 11, 2025");
    expect(out).toMatch(/12:00\s?PM/);
  });

  it("formats Ethiopian dates with the time of day", () => {
    const out = formatInstant(INSTANT, { locale: "en", calendar: "ethiopian" });
    expect(out).toMatch(/^Meskerem 1, 2018 E\.C\., 12:00\s?PM$/);
  });

  it("uses Amharic month names for the am locale", () => {
    const out = formatInstant(INSTANT, { locale: "am", calendar: "ethiopian" });
    expect(out).toContain("መስከረም 1 ቀን 2018 ዓ.ም.");
  });

  it("respects an explicit timezone around midnight", () => {
    // 22:00Z on Sept 10 is already Sept 11 in Addis (+03:00) but still Sept 10 in UTC.
    const instant = "2025-09-10T22:00:00Z";
    expect(formatInstant(instant, { locale: "en", calendar: "gregorian" })).toContain("September 11");
    expect(
      formatInstant(instant, { locale: "en", calendar: "gregorian", timeZone: "UTC" }),
    ).toContain("September 10");
  });

  it("returns an empty string for invalid input", () => {
    expect(formatInstant("not a date", { locale: "en", calendar: "gregorian" })).toBe("");
  });
});

describe("calendar preference helpers", () => {
  it("defaults to Ethiopian only for Amharic", () => {
    expect(defaultCalendarFor("am")).toBe("ethiopian");
    expect(defaultCalendarFor("en")).toBe("gregorian");
  });

  it("validates stored values", () => {
    expect(isCalendarPreference("ethiopian")).toBe(true);
    expect(isCalendarPreference("julian")).toBe(false);
    expect(isCalendarPreference(null)).toBe(false);
  });
});
