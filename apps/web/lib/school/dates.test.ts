import { describe, expect, it } from "vitest";
import { formatCivilDate, parseIsoDate } from "./dates";

describe("formatCivilDate", () => {
  it("formats Gregorian dates without shifting the day", () => {
    expect(formatCivilDate("2026-09-01", { locale: "en", calendar: "gregorian" })).toBe("Sep 1, 2026");
  });

  it("formats Ethiopian dates", () => {
    // Meskerem 1, 2019 E.C. starts on 11 Sep 2026
    expect(formatCivilDate("2026-09-11", { locale: "en", calendar: "ethiopian" })).toBe("Meskerem 1, 2019");
  });

  it("returns what it cannot read", () => {
    expect(formatCivilDate("soon", { locale: "en", calendar: "gregorian" })).toBe("soon");
    expect(formatCivilDate(undefined, { locale: "en", calendar: "gregorian" })).toBe("");
    expect(parseIsoDate("2026-13-01")).toBeNull();
  });
});
