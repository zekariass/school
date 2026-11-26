import { describe, expect, it } from "vitest";
import { bucketItems, busyDays, daySpan, entriesOn, isCancelled, isEventItem, needsZoneNote, sameWallClock, sourcesParam, type CalendarItem } from "./items";

const ADDIS = "Africa/Addis_Ababa";
const NY = "America/New_York";

function timed(id: string, startAt: string, endAt: string, timeZone = ADDIS, extra: Partial<CalendarItem> = {}): CalendarItem {
  return { id, source: "EVENT", kind: "SCHOOL_EVENT", title: id, allDay: false, startAt, endAt, timeZone, status: "SCHEDULED", ...extra };
}

function allDay(id: string, startDate: string, endDate: string, timeZone = ADDIS, extra: Partial<CalendarItem> = {}): CalendarItem {
  // endAt is exclusive: the start of the day after the last one (here shown in UTC for brevity, it must not be used).
  return {
    id,
    source: "EVENT",
    kind: "SCHOOL_EVENT",
    title: id,
    allDay: true,
    startDate,
    endDate,
    startAt: `${startDate}T00:00:00Z`,
    endAt: `${endDate}T23:59:59Z`,
    timeZone,
    status: "SCHEDULED",
    ...extra,
  };
}

const RANGE = { from: "2026-12-01", to: "2026-12-31" };

describe("daySpan", () => {
  it("takes the dates of an all-day item, never its instants", () => {
    // the instants are deliberately misleading: only the dates count
    expect(daySpan(allDay("a", "2026-12-07", "2026-12-09"))).toEqual({ first: "2026-12-07", last: "2026-12-09" });
    expect(daySpan(allDay("a", "2026-12-07", "2026-12-07", NY))).toEqual({ first: "2026-12-07", last: "2026-12-07" });
  });

  it("places a timed item by its instants in its own zone", () => {
    // 20:30 UTC = 23:30 in Addis (UTC+3), 15:30 in New York
    expect(daySpan(timed("t", "2026-12-07T20:30:00Z", "2026-12-07T21:00:00Z"))).toEqual({ first: "2026-12-07", last: "2026-12-07" });
    // 21:30 UTC is already the next day in Addis
    expect(daySpan(timed("t", "2026-12-07T21:30:00Z", "2026-12-07T22:30:00Z"))).toEqual({ first: "2026-12-08", last: "2026-12-08" });
    expect(daySpan(timed("t", "2026-12-07T21:30:00Z", "2026-12-07T22:30:00Z", NY))).toEqual({ first: "2026-12-07", last: "2026-12-07" });
  });

  it("spans two days when a timed item runs past midnight, but not when it ends exactly at midnight", () => {
    expect(daySpan(timed("t", "2026-12-07T20:00:00Z", "2026-12-07T22:00:00Z"))).toEqual({ first: "2026-12-07", last: "2026-12-08" });
    // ends at 00:00 Addis on the 8th: nothing of the 8th is occupied
    expect(daySpan(timed("t", "2026-12-07T18:00:00Z", "2026-12-07T21:00:00Z"))).toEqual({ first: "2026-12-07", last: "2026-12-07" });
    // zero length stays on its day
    expect(daySpan(timed("t", "2026-12-07T09:00:00Z", "2026-12-07T09:00:00Z"))).toEqual({ first: "2026-12-07", last: "2026-12-07" });
  });

  it("treats the short and the long day of a daylight-saving change as ordinary days", () => {
    // New York, spring forward 8 March 2026: the day is 23 hours long (00:00 EST = 05:00Z, next midnight 04:00Z)
    expect(daySpan(timed("t", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", NY))).toEqual({ first: "2026-03-08", last: "2026-03-08" });
    // the nonexistent 02:xx hour: 01:30 EST to 03:30 EDT is one evening
    expect(daySpan(timed("t", "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z", NY))).toEqual({ first: "2026-03-08", last: "2026-03-08" });
    // fall back 1 November 2026: the day is 25 hours long; 23:30 EST (04:30Z on the 2nd) is still the 1st
    expect(daySpan(timed("t", "2026-11-02T04:30:00Z", "2026-11-02T04:45:00Z", NY))).toEqual({ first: "2026-11-01", last: "2026-11-01" });
    expect(daySpan(timed("t", "2026-11-02T05:00:00Z", "2026-11-02T05:30:00Z", NY))).toEqual({ first: "2026-11-02", last: "2026-11-02" });
    // the whole 25-hour day is one day
    expect(daySpan(timed("t", "2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", NY))).toEqual({ first: "2026-11-01", last: "2026-11-01" });
  });
});

describe("bucketItems", () => {
  it("puts a one-day all-day item on its day", () => {
    const buckets = bucketItems([allDay("holiday", "2026-12-25", "2026-12-25")], RANGE);
    expect([...buckets.keys()]).toEqual(["2026-12-25"]);
    expect(entriesOn(buckets, "2026-12-25")[0]).toMatchObject({ index: 0, days: 1, startsToday: true, endsToday: true });
    expect(entriesOn(buckets, "2026-12-24")).toEqual([]);
  });

  it("spreads a multi-day item over its days, marking where it starts and ends", () => {
    const buckets = bucketItems([allDay("camp", "2026-12-07", "2026-12-09")], RANGE);
    expect(busyDays(buckets)).toEqual(["2026-12-07", "2026-12-08", "2026-12-09"]);
    const middle = entriesOn(buckets, "2026-12-08")[0]!;
    expect(middle).toMatchObject({ index: 1, days: 3, startsToday: false, endsToday: false });
    expect(entriesOn(buckets, "2026-12-09")[0]).toMatchObject({ index: 2, endsToday: true });
  });

  it("clips a multi-day item to the range but keeps its position in the span", () => {
    const buckets = bucketItems([allDay("break", "2026-11-28", "2026-12-03"), allDay("holiday", "2026-12-30", "2027-01-02")], RANGE);
    expect(busyDays(buckets)).toEqual(["2026-12-01", "2026-12-02", "2026-12-03", "2026-12-30", "2026-12-31"]);
    expect(entriesOn(buckets, "2026-12-01")[0]).toMatchObject({ index: 3, days: 6, startsToday: false });
    expect(entriesOn(buckets, "2026-12-31")[0]).toMatchObject({ index: 1, days: 4, endsToday: false });
  });

  it("skips items outside the range", () => {
    const buckets = bucketItems([allDay("old", "2026-11-01", "2026-11-05"), timed("later", "2027-02-01T08:00:00Z", "2027-02-01T09:00:00Z")], RANGE);
    expect(buckets.size).toBe(0);
  });

  it("places timed items on the day of their own zone, whatever the viewer's", () => {
    const late = timed("late", "2026-12-07T21:30:00Z", "2026-12-07T22:30:00Z"); // 00:30 on the 8th in Addis
    const ny = timed("ny", "2026-12-07T21:30:00Z", "2026-12-07T22:30:00Z", NY); // 16:30 on the 7th in New York
    const buckets = bucketItems([late, ny], RANGE);
    expect(entriesOn(buckets, "2026-12-08").map((e) => e.item.id)).toEqual(["late"]);
    expect(entriesOn(buckets, "2026-12-07").map((e) => e.item.id)).toEqual(["ny"]);
  });

  it("orders a day: all-day and multi-day first, then by start, then by id", () => {
    const items = [
      timed("b-lunch", "2026-12-07T09:00:00Z", "2026-12-07T10:00:00Z"),
      timed("a-early", "2026-12-07T05:00:00Z", "2026-12-07T06:00:00Z"),
      allDay("z-holiday", "2026-12-07", "2026-12-07"),
      timed("c-same", "2026-12-07T09:00:00Z", "2026-12-07T09:30:00Z"),
      timed("overnight", "2026-12-06T20:00:00Z", "2026-12-07T22:00:00Z"),
    ];
    const order = entriesOn(bucketItems(items, RANGE), "2026-12-07").map((e) => e.item.id);
    expect(order).toEqual(["overnight", "z-holiday", "a-early", "b-lunch", "c-same"]);
  });

  it("keeps cancelled items, flagged for a strike-through", () => {
    const cancelled = timed("c", "2026-12-07T09:00:00Z", "2026-12-07T10:00:00Z", ADDIS, { status: "CANCELLED" });
    const buckets = bucketItems([cancelled], RANGE);
    const entry = entriesOn(buckets, "2026-12-07")[0]!;
    expect(entry.item.id).toBe("c");
    expect(isCancelled(entry.item)).toBe(true);
    expect(isCancelled(timed("x", "2026-12-07T09:00:00Z", "2026-12-07T10:00:00Z"))).toBe(false);
  });

  it("keeps the occurrences of a series apart", () => {
    const a = timed("evt@20261207T060000Z", "2026-12-07T06:00:00Z", "2026-12-07T07:00:00Z", ADDIS, { eventId: "e", occurrenceId: "20261207T060000Z", recurring: true });
    const b = timed("evt@20261214T060000Z", "2026-12-14T06:00:00Z", "2026-12-14T07:00:00Z", ADDIS, { eventId: "e", occurrenceId: "20261214T060000Z", recurring: true });
    expect(busyDays(bucketItems([b, a], RANGE))).toEqual(["2026-12-07", "2026-12-14"]);
  });
});

describe("sources and filters", () => {
  it("always names the sources that are on, so personal events are included on request", () => {
    expect(sourcesParam(new Set(["events", "holidays", "academic", "personal"]))).toBe("EVENT,HOLIDAY,ACADEMIC_PERIOD,PERSONAL_EVENT");
    expect(sourcesParam(new Set(["holidays"]))).toBe("HOLIDAY");
    expect(sourcesParam(new Set(["personal", "events"]))).toBe("EVENT,PERSONAL_EVENT");
    expect(sourcesParam(new Set())).toBe("");
  });

  it("opens the detail only for events", () => {
    expect(isEventItem({ source: "EVENT", eventId: "x" })).toBe(true);
    expect(isEventItem({ source: "PERSONAL_EVENT", eventId: "x" })).toBe(true);
    expect(isEventItem({ source: "HOLIDAY" })).toBe(false);
    expect(isEventItem({ source: "ACADEMIC_PERIOD" })).toBe(false);
    expect(isEventItem({ source: "EVENT" })).toBe(false);
  });
});

describe("time zone notes", () => {
  it("compares wall clocks, not names", () => {
    const instant = "2026-12-07T09:00:00Z";
    expect(sameWallClock(ADDIS, ADDIS, instant)).toBe(true);
    expect(sameWallClock(ADDIS, "Africa/Nairobi", instant)).toBe(true);
    expect(sameWallClock(ADDIS, NY, instant)).toBe(false);
    expect(sameWallClock(ADDIS, "Not/AZone", instant)).toBe(false);
    // a zone with daylight saving agrees with a fixed one only part of the year
    expect(sameWallClock("Europe/London", "UTC", "2026-01-15T12:00:00Z")).toBe(true);
    expect(sameWallClock("Europe/London", "UTC", "2026-07-15T12:00:00Z")).toBe(false);
  });

  it("asks for a note on timed items in another zone only", () => {
    const item = timed("t", "2026-12-07T09:00:00Z", "2026-12-07T10:00:00Z");
    expect(needsZoneNote(item, NY)).toBe(true);
    expect(needsZoneNote(item, ADDIS)).toBe(false);
    expect(needsZoneNote(item, null)).toBe(false);
    expect(needsZoneNote(allDay("d", "2026-12-07", "2026-12-07"), NY)).toBe(false);
  });
});
