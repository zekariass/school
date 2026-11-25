import { describe, expect, it } from "vitest";
import { canAwardHome, sectionsOf } from "./sections";

describe("sectionsOf", () => {
  it("keeps school and home apart and never sums them", () => {
    const sections = sectionsOf({ school: { total: 12 }, home: { total: 5 } });
    expect(sections.school.total).toBe(12);
    expect(sections.home?.total).toBe(5);
    expect(Object.keys(sections).sort()).toEqual(["home", "school"]);
  });

  it("has no home section when the API sent none", () => {
    expect(sectionsOf({ school: { total: 1 } }).home).toBeNull();
  });
});

describe("canAwardHome", () => {
  it("needs the link's canAwardPoints flag", () => {
    expect(canAwardHome({ canAwardPoints: true })).toBe(true);
    expect(canAwardHome({ canAwardPoints: false })).toBe(false);
    expect(canAwardHome(undefined)).toBe(false);
  });
});
