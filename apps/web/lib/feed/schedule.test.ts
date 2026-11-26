import { describe, expect, it } from "vitest";
import { checkSchedule, defaultScheduleLocal, instantToLocal, localToInstant, scheduleBounds } from "./schedule";

const ADDIS = "Africa/Addis_Ababa"; // UTC+3, no daylight saving
const NEW_YORK = "America/New_York";

describe("localToInstant", () => {
  it("reads a wall-clock time in the school's zone, whatever the browser's zone is", () => {
    expect(localToInstant("2026-12-01T09:30", ADDIS)?.toISOString()).toBe("2026-12-01T06:30:00.000Z");
    expect(localToInstant("2026-12-01T09:30", "UTC")?.toISOString()).toBe("2026-12-01T09:30:00.000Z");
    expect(localToInstant("2026-12-01T09:30", "Asia/Kolkata")?.toISOString()).toBe("2026-12-01T04:00:00.000Z");
  });

  it("follows daylight saving of the school's zone", () => {
    expect(localToInstant("2026-01-15T09:00", NEW_YORK)?.toISOString()).toBe("2026-01-15T14:00:00.000Z");
    expect(localToInstant("2026-07-15T09:00", NEW_YORK)?.toISOString()).toBe("2026-07-15T13:00:00.000Z");
  });

  it("refuses a time that does not exist (the spring-forward gap) and malformed input", () => {
    expect(localToInstant("2026-03-08T02:30", NEW_YORK)).toBeNull();
    expect(localToInstant("2026-02-30T10:00", ADDIS)).toBeNull();
    expect(localToInstant("2026-12-01 09:30", ADDIS)).toBeNull();
    expect(localToInstant("", ADDIS)).toBeNull();
    expect(localToInstant("2026-12-01T09:30", "Not/AZone")).toBeNull();
  });
});

describe("instantToLocal", () => {
  it("round-trips with localToInstant", () => {
    for (const zone of [ADDIS, NEW_YORK, "UTC", "Asia/Kathmandu"]) {
      const instant = localToInstant("2026-11-30T17:45", zone);
      expect(instant).not.toBeNull();
      expect(instantToLocal(instant!, zone)).toBe("2026-11-30T17:45");
    }
  });

  it("shows the school's wall clock, not the browser's", () => {
    expect(instantToLocal("2026-12-01T21:30:00Z", ADDIS)).toBe("2026-12-02T00:30");
    expect(instantToLocal("not a date", ADDIS)).toBeNull();
    expect(instantToLocal("2026-12-01T21:30:00Z", "Nope")).toBeNull();
  });
});

describe("checkSchedule", () => {
  const now = new Date("2026-11-26T10:00:00Z");

  it("returns the UTC instant to send", () => {
    expect(checkSchedule("2026-11-27T08:00", ADDIS, now)).toEqual({ ok: true, instant: "2026-11-27T05:00:00.000Z" });
  });

  it("refuses the past, the next minute and anything beyond 90 days", () => {
    expect(checkSchedule("2026-11-26T12:00", ADDIS, now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2026-11-26T13:00", ADDIS, now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2026-11-26T13:01", ADDIS, now)).toEqual({ ok: true, instant: "2026-11-26T10:01:00.000Z" });
    expect(checkSchedule("2027-02-24T12:00", ADDIS, now).ok).toBe(true);
    expect(checkSchedule("2027-02-24T14:00", ADDIS, now)).toEqual({ ok: false, reason: "tooFar" });
  });

  it("reports an invalid value", () => {
    expect(checkSchedule("tomorrow", ADDIS, now)).toEqual({ ok: false, reason: "invalid" });
  });
});

describe("bounds and default", () => {
  const now = new Date("2026-11-26T10:00:00Z");

  it("gives the input's min and max on the school's clock", () => {
    expect(scheduleBounds(ADDIS, now)).toEqual({ min: "2026-11-26T13:01", max: "2027-02-24T13:00" });
    expect(scheduleBounds("Nope", now)).toBeNull();
  });

  it("suggests tomorrow at 08:00 on the school's clock", () => {
    // 22:30 UTC is already 01:30 on the 27th in Addis: "tomorrow" follows the school's calendar day, not UTC's.
    expect(defaultScheduleLocal(ADDIS, new Date("2026-11-26T22:30:00Z"))).toBe("2026-11-28T08:00");
    expect(defaultScheduleLocal(ADDIS, now)).toBe("2026-11-27T08:00");
  });
});
