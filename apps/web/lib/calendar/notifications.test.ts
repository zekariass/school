import { describe, expect, it } from "vitest";
import { isCalendarNotification } from "./notifications";

describe("isCalendarNotification", () => {
  it("recognises the event notifications by their path", () => {
    expect(isCalendarNotification({ path: "/calendar/events/0b9d8f6c-3f4e-4a63-9d86-0f9b2c7a1e55" })).toBe(true);
    expect(isCalendarNotification({ path: "/calendar" })).toBe(true);
  });

  it("ignores everything else", () => {
    expect(isCalendarNotification({ path: "/announcements/1" })).toBe(false);
    expect(isCalendarNotification({ path: "/calendars" })).toBe(false);
    expect(isCalendarNotification({ path: 5 })).toBe(false);
    expect(isCalendarNotification(null)).toBe(false);
    expect(isCalendarNotification(undefined)).toBe(false);
  });
});
