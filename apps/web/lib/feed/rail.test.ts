import { describe, expect, it } from "vitest";
import type { CalendarItem } from "@/lib/calendar/items";
import { UPCOMING_COUNT, pinnedAnnouncement, upcomingItems, upcomingWindow } from "./rail";

describe("pinnedAnnouncement", () => {
  it("is the newest published announcement, skipping a withdrawn one", () => {
    const cards = [
      { id: "w", status: "WITHDRAWN" },
      { id: "p", status: "PUBLISHED" },
      { id: "q", status: "PUBLISHED" },
    ];
    expect(pinnedAnnouncement(cards)?.id).toBe("p");
  });
  it("is nothing when there is nothing to show", () => {
    expect(pinnedAnnouncement([])).toBeUndefined();
    expect(pinnedAnnouncement(undefined)).toBeUndefined();
    expect(pinnedAnnouncement([{ id: "w", status: "WITHDRAWN" }])).toBeUndefined();
  });
});

function item(id: string, startAt: string, endAt: string, extra: Partial<CalendarItem> = {}): CalendarItem {
  return { id, title: id, source: "EVENT", kind: "SCHOOL_EVENT", allDay: false, startAt, endAt, timeZone: "UTC", status: "SCHEDULED", ...extra } as CalendarItem;
}

describe("upcomingItems", () => {
  const now = new Date("2026-12-01T10:00:00Z");

  it("keeps the next events soonest first, at most three", () => {
    const items = [
      item("d", "2026-12-20T09:00:00Z", "2026-12-20T10:00:00Z"),
      item("a", "2026-12-02T09:00:00Z", "2026-12-02T10:00:00Z"),
      item("c", "2026-12-12T09:00:00Z", "2026-12-12T10:00:00Z"),
      item("b", "2026-12-05T09:00:00Z", "2026-12-05T10:00:00Z"),
    ];
    expect(upcomingItems(items, now).map((i) => i.id)).toEqual(["a", "b", "c"]);
    expect(UPCOMING_COUNT).toBe(3);
  });

  it("drops cancelled events and events that are over, keeps one that is under way", () => {
    const items = [
      item("over", "2026-12-01T08:00:00Z", "2026-12-01T09:00:00Z"),
      item("cancelled", "2026-12-03T08:00:00Z", "2026-12-03T09:00:00Z", { status: "CANCELLED" }),
      item("now", "2026-12-01T09:30:00Z", "2026-12-01T11:00:00Z"),
    ];
    expect(upcomingItems(items, now).map((i) => i.id)).toEqual(["now"]);
  });

  it("answers nothing for nothing", () => {
    expect(upcomingItems([], now)).toEqual([]);
  });
});

describe("upcomingWindow", () => {
  it("starts the day before today in UTC and stays under the API's limit", () => {
    const w = upcomingWindow("2026-12-01");
    expect(w.from).toBe("2026-11-30T00:00:00Z");
    expect(new Date(w.to).getTime()).toBeGreaterThan(new Date("2027-01-30T00:00:00Z").getTime());
  });
});
