import { describe, expect, it } from "vitest";
import { asBadgeType, badgeCriteria, BADGE_CODE, slugFromName } from "./badges";

describe("badgeCriteria", () => {
  const fields = { threshold: "50", days: "5", categoryId: "" };

  it("sends nothing for a hand-given badge", () => {
    expect(badgeCriteria("MANUAL", fields)).toBeNull();
  });

  it("builds the rule of each type from the typed numbers", () => {
    expect(badgeCriteria("POINTS_TOTAL", fields)).toEqual({ threshold: 50 });
    expect(badgeCriteria("CATEGORY_TOTAL", { ...fields, categoryId: "c1" })).toEqual({ threshold: 50, categoryId: "c1" });
    expect(badgeCriteria("ATTENDANCE_STREAK", fields)).toEqual({ days: 5 });
  });

  it("rejects numbers outside the API's range or not whole", () => {
    expect(badgeCriteria("POINTS_TOTAL", { ...fields, threshold: "0" })).toBeNull();
    expect(badgeCriteria("POINTS_TOTAL", { ...fields, threshold: "100001" })).toBeNull();
    expect(badgeCriteria("POINTS_TOTAL", { ...fields, threshold: "2.5" })).toBeNull();
    expect(badgeCriteria("POINTS_TOTAL", { ...fields, threshold: "" })).toBeNull();
    expect(badgeCriteria("ATTENDANCE_STREAK", { ...fields, days: "366" })).toBeNull();
  });
});

describe("codes", () => {
  it("makes a code from a name", () => {
    expect(slugFromName("Super Helper!")).toBe("super_helper");
    expect(slugFromName("  Café  ")).toBe("cafe");
    expect(slugFromName("123")).toBe("");
    expect(slugFromName("አማርኛ")).toBe("");
  });

  it("matches the API's code format", () => {
    expect(BADGE_CODE.test("super_helper")).toBe(true);
    expect(BADGE_CODE.test("Super")).toBe(false);
    expect(BADGE_CODE.test("a")).toBe(false);
    expect(BADGE_CODE.test("1abc")).toBe(false);
  });

  it("knows the badge types", () => {
    expect(asBadgeType("MANUAL")).toBe("MANUAL");
    expect(asBadgeType("FUTURE_TYPE")).toBeNull();
  });
});
