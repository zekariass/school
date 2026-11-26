import { describe, expect, it } from "vitest";
import { ANNOUNCEMENT_SETTING_KEYS, ANNOUNCEMENTS_MODULE, announcementSettingId } from "./settings";

describe("announcementSettingId", () => {
  it("maps every announcement setting to its localization id", () => {
    expect(announcementSettingId("announcements.teachers_can_announce")).toBe("teachersCanAnnounce");
    expect(announcementSettingId("announcements.sms.max_per_day")).toBe("smsMaxPerDay");
    expect(announcementSettingId("announcements.reminder.min_hours")).toBe("reminderMinHours");
    expect(ANNOUNCEMENT_SETTING_KEYS).toHaveLength(9);
    expect(ANNOUNCEMENT_SETTING_KEYS.every((k) => k.startsWith(`${ANNOUNCEMENTS_MODULE}.`))).toBe(true);
  });

  it("knows nothing about other keys, including inherited property names", () => {
    expect(announcementSettingId("feed.pin_limit")).toBeNull();
    expect(announcementSettingId("constructor")).toBeNull();
    expect(announcementSettingId("__proto__")).toBeNull();
  });
});
