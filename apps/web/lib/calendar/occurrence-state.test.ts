import { describe, expect, it } from "vitest";
import { occurrenceState } from "./occurrence";
import type { EventView } from "./rsvp";

const series: EventView = {
  id: "e",
  eventType: "SCHOOL_EVENT",
  scope: "SCHOOL",
  status: "SCHEDULED",
  title: "Choir",
  description: "Weekly rehearsal",
  location: "Hall",
  timeZone: "Africa/Addis_Ababa",
  recurring: true,
  recurrenceRule: "FREQ=WEEKLY;COUNT=4",
  allDay: false,
  startAt: "2026-12-07T06:00:00Z",
  endAt: "2026-12-07T07:30:00Z",
};

describe("occurrenceState", () => {
  it("gives an unchanged occurrence the series' values at its own place in the series", () => {
    expect(occurrenceState(series, "20261214T060000Z")).toMatchObject({
      title: "Choir",
      location: "Hall",
      description: "Weekly rehearsal",
      startAt: "2026-12-14T06:00:00.000Z",
      endAt: "2026-12-14T07:30:00.000Z",
      status: "SCHEDULED",
      modified: false,
      reason: null,
      timeZone: "Africa/Addis_Ababa",
    });
  });

  it("applies a cancellation with its reason", () => {
    const view: EventView = { ...series, overrides: [{ occurrenceId: "20261214T060000Z", status: "CANCELLED", reason: "Holiday" }] };
    expect(occurrenceState(view, "20261214T060000Z")).toMatchObject({ status: "CANCELLED", reason: "Holiday", modified: false });
    expect(occurrenceState(view, "20261221T060000Z")).toMatchObject({ status: "SCHEDULED" });
  });

  it("applies a change: the moved time and the text of the exception, the id staying the one of the series", () => {
    const view: EventView = {
      ...series,
      overrides: [{ occurrenceId: "20261214T060000Z", status: "MODIFIED", startAt: "2026-12-15T07:00:00Z", endAt: "2026-12-15T08:30:00Z", location: "Music room", title: "Choir (extra)" }],
    };
    expect(occurrenceState(view, "20261214T060000Z")).toMatchObject({
      title: "Choir (extra)",
      location: "Music room",
      description: "Weekly rehearsal",
      startAt: "2026-12-15T07:00:00Z",
      endAt: "2026-12-15T08:30:00Z",
      modified: true,
      status: "SCHEDULED",
    });
  });

  it("works out all-day occurrences by dates in the zone, keeping the length", () => {
    const allDay: EventView = { ...series, allDay: true, startDate: "2026-12-07", endDate: "2026-12-08", startAt: "2026-12-06T21:00:00Z", endAt: "2026-12-08T21:00:00Z" };
    // the occurrence id is the instant of the start of the day in the zone: 21:00Z is already the next day in Addis
    expect(occurrenceState(allDay, "20261213T210000Z")).toMatchObject({ allDay: true, startDate: "2026-12-14", endDate: "2026-12-15" });
  });

  it("is null for something that is not an occurrence id", () => {
    expect(occurrenceState(series, "nope")).toBeNull();
  });
});
