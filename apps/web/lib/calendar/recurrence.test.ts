import { describe, expect, it } from "vitest";
import {
  addYears,
  buildRule,
  effectiveDays,
  noRecurrence,
  parseRule,
  recurrenceProblems,
  summarize,
  summarizeRule,
  untilLimit,
  weekdayCode,
  type RecurrenceDraft,
} from "./recurrence";

// 2026-12-07 is a Monday.
const START = "2026-12-07";
const draft = (patch: Partial<RecurrenceDraft>): RecurrenceDraft => ({ ...noRecurrence(), freq: "WEEKLY", ...patch });

describe("buildRule", () => {
  it("sends nothing for an event that does not repeat", () => {
    expect(buildRule(noRecurrence(), START)).toBeNull();
  });

  it("builds daily rules with an interval and an end", () => {
    expect(buildRule(draft({ freq: "DAILY" }), START)).toBe("FREQ=DAILY");
    expect(buildRule(draft({ freq: "DAILY", interval: 2, end: "count", count: 5 }), START)).toBe("FREQ=DAILY;INTERVAL=2;COUNT=5");
    expect(buildRule(draft({ freq: "DAILY", end: "until", until: "2026-12-31" }), START)).toBe("FREQ=DAILY;UNTIL=20261231");
  });

  it("builds weekly rules; the start's weekday is always part of them", () => {
    expect(buildRule(draft({}), START)).toBe("FREQ=WEEKLY");
    // Wednesday only was chosen, but the series starts on a Monday: Monday stays, as the API requires
    expect(buildRule(draft({ byDay: ["WE"] }), START)).toBe("FREQ=WEEKLY;BYDAY=MO,WE");
    expect(buildRule(draft({ byDay: ["FR", "MO", "WE"], interval: 2, end: "count", count: 10 }), START)).toBe("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=10");
    // Sunday sorts last (the week starts on Monday)
    expect(buildRule(draft({ byDay: ["SU"] }), START)).toBe("FREQ=WEEKLY;BYDAY=MO,SU");
  });

  it("builds monthly rules on the start's day of the month", () => {
    expect(buildRule(draft({ freq: "MONTHLY" }), START)).toBe("FREQ=MONTHLY;BYMONTHDAY=7");
    expect(buildRule(draft({ freq: "MONTHLY", interval: 3, end: "until", until: "2027-12-07" }), "2026-12-31")).toBe("FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=31;UNTIL=20271207");
  });

  it("ignores values of an end the person did not choose", () => {
    expect(buildRule(draft({ freq: "DAILY", end: "never", until: "2027-01-01", count: 4 }), START)).toBe("FREQ=DAILY");
    expect(buildRule(draft({ freq: "DAILY", end: "count", until: "2027-01-01", count: 4 }), START)).toBe("FREQ=DAILY;COUNT=4");
  });

  it("knows the weekday of a day", () => {
    expect(weekdayCode(START)).toBe("MO");
    expect(effectiveDays(draft({ byDay: [] }), "2026-12-06")).toEqual(["SU"]);
  });
});

describe("recurrenceProblems", () => {
  it("accepts a plain rule and ignores events that do not repeat", () => {
    expect(recurrenceProblems(draft({}), START)).toEqual([]);
    expect(recurrenceProblems({ ...noRecurrence(), interval: 0 }, START)).toEqual([]);
  });

  it("limits the interval to 1-99", () => {
    expect(recurrenceProblems(draft({ interval: 0 }), START)).toEqual(["intervalRange"]);
    expect(recurrenceProblems(draft({ interval: 100 }), START)).toEqual(["intervalRange"]);
    expect(recurrenceProblems(draft({ interval: null }), START)).toEqual(["intervalRange"]);
    expect(recurrenceProblems(draft({ interval: 1.5 }), START)).toEqual(["intervalRange"]);
    expect(recurrenceProblems(draft({ interval: 99 }), START)).toEqual([]);
  });

  it("limits the count to 1-1000", () => {
    expect(recurrenceProblems(draft({ end: "count", count: 0 }), START)).toEqual(["countRange"]);
    expect(recurrenceProblems(draft({ end: "count", count: 1001 }), START)).toEqual(["countRange"]);
    expect(recurrenceProblems(draft({ end: "count", count: null }), START)).toEqual(["countRange"]);
    expect(recurrenceProblems(draft({ end: "count", count: 1000 }), START)).toEqual([]);
  });

  it("needs an until date between the start and ten years after it", () => {
    expect(recurrenceProblems(draft({ end: "until", until: "" }), START)).toEqual(["untilRequired"]);
    expect(recurrenceProblems(draft({ end: "until", until: "2026-12-06" }), START)).toEqual(["untilBeforeStart"]);
    expect(recurrenceProblems(draft({ end: "until", until: START }), START)).toEqual([]);
    expect(recurrenceProblems(draft({ end: "until", until: "2036-12-07" }), START)).toEqual([]);
    expect(recurrenceProblems(draft({ end: "until", until: "2036-12-08" }), START)).toEqual(["untilTooFar"]);
  });

  it("computes ten years like the API (Feb 29 becomes Feb 28)", () => {
    expect(untilLimit("2028-02-29")).toBe("2038-02-28");
    expect(addYears("2026-12-07", 10)).toBe("2036-12-07");
    expect(addYears("2028-02-29", 4)).toBe("2032-02-29");
  });
});

describe("parseRule", () => {
  it("reads back what it builds", () => {
    for (const d of [
      draft({ freq: "DAILY", interval: 2, end: "count", count: 5 }),
      draft({ byDay: ["MO", "WE"], end: "until", until: "2027-03-01" }),
      draft({ freq: "MONTHLY", end: "never" }),
    ]) {
      const rule = buildRule(d, START)!;
      const back = parseRule(rule, "Africa/Addis_Ababa");
      expect(back).not.toBeNull();
      expect(buildRule(back!, START)).toBe(rule);
    }
  });

  it("reads the canonical stored form, RRULE prefix and until instants", () => {
    expect(parseRule("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4", "UTC")).toMatchObject({ freq: "WEEKLY", byDay: ["MO", "WE"], end: "count", count: 4 });
    expect(parseRule("RRULE:FREQ=DAILY", "UTC")).toMatchObject({ freq: "DAILY", end: "never", interval: 1 });
    // an until instant becomes the date it falls on in the event's zone: 22:00 UTC is already the next day in Addis
    expect(parseRule("FREQ=DAILY;UNTIL=20261203T220000Z", "Africa/Addis_Ababa")).toMatchObject({ end: "until", until: "2026-12-04" });
    expect(parseRule("FREQ=DAILY;UNTIL=20261203T220000Z", "UTC")).toMatchObject({ until: "2026-12-03" });
    expect(parseRule(null, "UTC")).toEqual(noRecurrence());
  });

  it("returns null for rules the builder cannot show, so the form keeps them as they are", () => {
    for (const rule of ["FREQ=YEARLY", "FREQ=WEEKLY;BYDAY=2TU", "FREQ=MONTHLY;BYMONTHDAY=1,15", "garbage", "FREQ=DAILY;BYSETPOS=1", "FREQ=DAILY;UNTIL=2026", "FREQ=DAILY;WKST=SU"]) {
      expect(parseRule(rule, "UTC"), rule).toBeNull();
    }
  });
});

describe("summaries", () => {
  it("describes a draft as data for a sentence", () => {
    expect(summarize(noRecurrence(), START)).toBeNull();
    expect(summarize(draft({ freq: "DAILY" }), START)).toEqual({ freq: "DAILY", interval: 1, days: [], monthDay: null, end: { kind: "never" } });
    expect(summarize(draft({ byDay: ["WE", "FR"], interval: 2, end: "count", count: 6 }), START)).toEqual({
      freq: "WEEKLY",
      interval: 2,
      days: ["MO", "WE", "FR"],
      monthDay: null,
      end: { kind: "count", count: 6 },
    });
    expect(summarize(draft({ freq: "MONTHLY", end: "until", until: "2027-06-01" }), "2026-12-15")).toEqual({
      freq: "MONTHLY",
      interval: 1,
      days: [],
      monthDay: 15,
      end: { kind: "until", date: "2027-06-01" },
    });
  });

  it("describes a stored rule, or nothing when it is not one the builder reads", () => {
    expect(summarizeRule("FREQ=WEEKLY;COUNT=3", START, "UTC")).toEqual({ freq: "WEEKLY", interval: 1, days: ["MO"], monthDay: null, end: { kind: "count", count: 3 } });
    expect(summarizeRule(null, START, "UTC")).toBeNull();
    expect(summarizeRule("FREQ=YEARLY", START, "UTC")).toBeNull();
  });
});
