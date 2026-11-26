import { describe, expect, it } from "vitest";
import { MAX_SCHEDULE_DAYS, checkSchedule, defaultScheduleLocal, instantToLocal, scheduleBounds } from "./schedule";

const ADDIS = "Africa/Addis_Ababa"; // UTC+3, no daylight saving
const NEW_YORK = "America/New_York";
const now = new Date("2026-11-26T10:00:00Z");

describe("checkSchedule", () => {
  it("reads the picked time in the school's zone, not the browser's, and returns the UTC instant", () => {
    expect(checkSchedule("2026-11-27T08:00", ADDIS, now)).toEqual({ ok: true, instant: "2026-11-27T05:00:00.000Z" });
    expect(checkSchedule("2026-11-27T08:00", NEW_YORK, now)).toEqual({ ok: true, instant: "2026-11-27T13:00:00.000Z" });
    expect(checkSchedule("2026-11-27T08:00", "UTC", now)).toEqual({ ok: true, instant: "2026-11-27T08:00:00.000Z" });
  });

  it("refuses the past and the next minute", () => {
    expect(checkSchedule("2026-11-26T12:00", ADDIS, now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2026-11-26T13:00", ADDIS, now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2026-11-26T13:01", ADDIS, now)).toEqual({ ok: true, instant: "2026-11-26T10:01:00.000Z" });
  });

  it("allows 180 days ahead, not a minute more (the API's own limit)", () => {
    expect(MAX_SCHEDULE_DAYS).toBe(180);
    // 180 days after 2026-11-26 10:00Z is 2027-05-25 10:00Z = 13:00 in Addis
    expect(checkSchedule("2027-05-25T13:00", ADDIS, now).ok).toBe(true);
    expect(checkSchedule("2027-05-25T13:01", ADDIS, now)).toEqual({ ok: false, reason: "tooFar" });
    // 90 days (the feed's limit) is fine here
    expect(checkSchedule("2027-02-24T14:00", ADDIS, now).ok).toBe(true);
  });

  it("refuses malformed values and times that do not exist in the zone", () => {
    expect(checkSchedule("tomorrow", ADDIS, now)).toEqual({ ok: false, reason: "invalid" });
    expect(checkSchedule("", ADDIS, now)).toEqual({ ok: false, reason: "invalid" });
    expect(checkSchedule("2027-03-14T02:30", NEW_YORK, now)).toEqual({ ok: false, reason: "invalid" });
  });
});

describe("bounds and defaults", () => {
  it("shows the school's wall clock", () => {
    expect(scheduleBounds(ADDIS, now)?.min).toBe("2026-11-26T13:01");
    expect(scheduleBounds(ADDIS, now)?.max).toBe("2027-05-25T13:00");
    expect(instantToLocal("2026-12-01T21:30:00Z", ADDIS)).toBe("2026-12-02T00:30");
  });

  it("proposes tomorrow morning on the school's clock", () => {
    expect(defaultScheduleLocal(ADDIS, now)).toBe("2026-11-27T08:00");
    // late evening in UTC is already tomorrow in Addis: the proposal is the day after, on the school's calendar
    expect(defaultScheduleLocal(ADDIS, new Date("2026-11-26T22:30:00Z"))).toBe("2026-11-28T08:00");
    expect(defaultScheduleLocal("Not/AZone", now)).toBe("");
  });
});
