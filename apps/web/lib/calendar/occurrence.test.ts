import { describe, expect, it } from "vitest";
import { isOccurrenceId, itemRef, occurrenceIdOf, occurrenceInstant, parseItemId } from "./occurrence";

const EVENT = "0b9d8f6c-3f4e-4a63-9d86-0f9b2c7a1e55";

describe("occurrence ids", () => {
  it("accepts the UTC instant form and nothing else", () => {
    expect(isOccurrenceId("20261207T060000Z")).toBe(true);
    for (const bad of ["", "2026-12-07T06:00:00Z", "20261207T060000", "20261340T060000Z", "20260231T060000Z", "20261207T256000Z".replace("25", "99"), "../x", "20261207T060000Z/cancel", 42, null]) {
      expect(isOccurrenceId(bad), String(bad)).toBe(false);
    }
  });

  it("converts between ids and instants", () => {
    expect(occurrenceInstant("20261207T060000Z")?.toISOString()).toBe("2026-12-07T06:00:00.000Z");
    expect(occurrenceIdOf(new Date("2026-12-07T06:00:00Z"))).toBe("20261207T060000Z");
    expect(occurrenceIdOf(occurrenceInstant("20270301T235959Z")!)).toBe("20270301T235959Z");
    expect(occurrenceInstant("20260229T060000Z")).toBeNull();
  });
});

describe("item ids", () => {
  it("splits an occurrence item id into the event and the occurrence", () => {
    expect(parseItemId(`${EVENT}@20261207T060000Z`)).toEqual({ eventId: EVENT, occurrenceId: "20261207T060000Z" });
    expect(parseItemId(EVENT)).toEqual({ eventId: EVENT, occurrenceId: null });
  });

  it("refuses ids that are not an event's", () => {
    expect(parseItemId("HOLIDAY:abc:school")).toBeNull();
    expect(parseItemId("PERIOD:1:START")).toBeNull();
    expect(parseItemId(`${EVENT}@nope`)).toBeNull();
    expect(parseItemId(`${EVENT}@20261207T060000Z/../x`)).toBeNull();
    expect(parseItemId("../etc@20261207T060000Z")).toBeNull();
  });

  it("prefers the fields of an item and validates them", () => {
    expect(itemRef({ id: "x", eventId: EVENT, occurrenceId: "20261207T060000Z" })).toEqual({ eventId: EVENT, occurrenceId: "20261207T060000Z" });
    expect(itemRef({ id: EVENT, eventId: EVENT })).toEqual({ eventId: EVENT, occurrenceId: null });
    expect(itemRef({ id: `${EVENT}@20261207T060000Z` })).toEqual({ eventId: EVENT, occurrenceId: "20261207T060000Z" });
    expect(itemRef({ id: "x", eventId: EVENT, occurrenceId: "bad" })).toBeNull();
    expect(itemRef({ id: "HOLIDAY:1" })).toBeNull();
  });
});
