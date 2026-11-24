import { describe, expect, it } from "vitest";
import { parseRealtimeEvent } from "./types";

const notification = { id: "n1", typeCode: "NEW_POST", title: "Hi", data: { path: "/" }, priority: "NORMAL", createdAt: "x" };

describe("parseRealtimeEvent", () => {
  it("reads the three event types", () => {
    expect(parseRealtimeEvent(JSON.stringify({ type: "notification.created", notification, unreadCount: 2 }))).toMatchObject({
      type: "notification.created",
      unreadCount: 2,
    });
    expect(parseRealtimeEvent(JSON.stringify({ type: "notification.read", ids: ["a"], all: false, unreadCount: 1 }))).toEqual({
      type: "notification.read",
      ids: ["a"],
      all: false,
      unreadCount: 1,
    });
    expect(parseRealtimeEvent(JSON.stringify({ type: "notification.archived", id: "a", unreadCount: 0 }))).toEqual({
      type: "notification.archived",
      id: "a",
      unreadCount: 0,
    });
  });

  it("defaults the optional fields of a read event", () => {
    expect(parseRealtimeEvent('{"type":"notification.read","unreadCount":0,"all":true}')).toEqual({
      type: "notification.read",
      ids: [],
      all: true,
      unreadCount: 0,
    });
  });

  it("ignores garbage and unknown shapes", () => {
    for (const body of ["", "nope", "[]", "null", '{"type":"other"}', '{"type":"notification.created"}', '{"type":"notification.read"}']) {
      expect(parseRealtimeEvent(body)).toBeNull();
    }
    expect(parseRealtimeEvent('{"type":"notification.read","unreadCount":-1}')).toBeNull();
  });
});
