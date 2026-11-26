import { describe, expect, it } from "vitest";
import { dayLabel, monthTitle, rangeLabel, shortDay, timeOfDay, timeRange, weekdayName, zoneLabel } from "./format";
import { CALENDAR_SETTING_IDS_LIST, CALENDAR_SETTING_KEYS, CALENDAR_MODULE, calendarSettingId } from "./settings";

describe("titles and labels", () => {
  it("names a month in either calendar, with the 13th month", () => {
    expect(monthTitle(2026, 12, "gregorian", "en")).toBe("December 2026");
    expect(monthTitle(2019, 3, "ethiopian", "en")).toBe("Hidar 2019");
    expect(monthTitle(2017, 13, "ethiopian", "en")).toBe("Pagume 2017");
    expect(monthTitle(2019, 3, "ethiopian", "am")).toBe("ኅዳር 2019");
    expect(monthTitle(2017, 13, "ethiopian", "am")).toBe("ጳጉሜን 2017");
  });

  it("names weekdays", () => {
    expect(weekdayName(1, "en")).toBe("Mon");
    expect(weekdayName(0, "en", "long")).toBe("Sunday");
    expect(weekdayName(6, "en", "long")).toBe("Saturday");
  });

  it("labels a day in the display calendar", () => {
    expect(dayLabel("2026-12-07", "gregorian", "en")).toBe("Monday, December 7, 2026");
    expect(dayLabel("2026-12-07", "ethiopian", "en")).toBe("Monday, Hidar 28, 2019 E.C.");
    expect(shortDay("2026-12-07", "gregorian", "en")).toBe("Dec 7");
    expect(shortDay("2026-12-07", "ethiopian", "en")).toBe("Hidar 28");
    expect(rangeLabel("2026-12-07", "2026-12-13", "gregorian", "en")).toBe("Dec 7 – Dec 13");
  });
});

describe("times in an explicit zone", () => {
  it("shows the wall clock of the zone asked for", () => {
    const instant = "2026-12-07T06:00:00Z";
    expect(timeOfDay(instant, "Africa/Addis_Ababa", "en")).toMatch(/^9:00\s?AM$/);
    expect(timeOfDay(instant, "America/New_York", "en")).toMatch(/^1:00\s?AM$/);
    expect(timeOfDay("nonsense", "UTC", "en")).toBe("");
  });

  it("shows a range, or a single time for a zero-length event", () => {
    expect(timeRange("2026-12-07T06:00:00Z", "2026-12-07T07:30:00Z", "Africa/Addis_Ababa", "en")).toMatch(/^9:00\s?AM – 10:30\s?AM$/);
    expect(timeRange("2026-12-07T06:00:00Z", "2026-12-07T06:00:00Z", "Africa/Addis_Ababa", "en")).toMatch(/^9:00\s?AM$/);
  });

  it("names the zone with its abbreviation where Intl has one", () => {
    expect(zoneLabel("America/New_York", "2026-12-07T06:00:00Z", "en")).toBe("America/New_York (EST)");
    expect(zoneLabel("America/New_York", "2026-07-07T06:00:00Z", "en")).toBe("America/New_York (EDT)");
    expect(zoneLabel("Not/AZone", "2026-07-07T06:00:00Z", "en")).toBe("Not/AZone");
  });
});

describe("calendar settings", () => {
  it("maps every calendar setting to its localization id", () => {
    expect(calendarSettingId("calendar.teachers_can_create_class_events")).toBe("teachersCanCreateClassEvents");
    expect(calendarSettingId("calendar.reminder.audience")).toBe("reminderAudience");
    expect(CALENDAR_SETTING_KEYS).toHaveLength(6);
    expect(new Set(CALENDAR_SETTING_IDS_LIST).size).toBe(6);
    expect(CALENDAR_SETTING_KEYS.every((k) => k.startsWith(`${CALENDAR_MODULE}.`))).toBe(true);
  });

  it("knows nothing about other keys, including inherited property names", () => {
    expect(calendarSettingId("announcements.sms.types")).toBeNull();
    expect(calendarSettingId("constructor")).toBeNull();
    expect(calendarSettingId("__proto__")).toBeNull();
  });
});
