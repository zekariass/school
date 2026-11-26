import { describe, expect, it } from "vitest";
import { resolveDeepLink } from "@/lib/notifications/deep-link";
import { conversation } from "./fixtures";
import { ALL_MESSAGING_KEYS, doorbellKeys, parseDoorbell, pollInterval } from "./doorbell";
import { isAboutOpenConversation, messagingDeepLink } from "./deep-link";
import { canEditNow, editMinutesLeft, editSecondsLeft, nextEditDeadlineMs } from "./edit-window";
import { flattenInbox, groupByChild, isMuted, lastMessageState, shouldGroupByChild, sumUnread, unreadBadge } from "./inbox";
import { contactName, initialsOf, personName } from "./participants";
import { checkReason, reasonRemaining } from "./review";
import { checkSchedule, defaultScheduleLocal, scheduleBounds } from "./schedule";

const ID = "0f9b6c1e-3b0a-4d7e-9a53-5f3a2a9d1c11";
const ID2 = "1f9b6c1e-3b0a-4d7e-9a53-5f3a2a9d1c22";

describe("unread counts", () => {
  it("shows nothing for zero, the number up to 99 and 99+ above", () => {
    expect(unreadBadge(0)).toBeNull();
    expect(unreadBadge(-3)).toBeNull();
    expect(unreadBadge(7)).toBe("7");
    expect(unreadBadge(99)).toBe("99");
    expect(unreadBadge(100)).toBe("99+");
  });

  it("sums only what the list shows", () => {
    expect(sumUnread([{ unreadCount: 2 }, { unreadCount: 3 }])).toBe(5);
  });

  it("mutes only while the mute lasts", () => {
    const now = new Date("2026-11-26T10:00:00Z");
    expect(isMuted({ muted: false }, now)).toBe(false);
    expect(isMuted({ muted: true, mutedUntil: "2026-11-26T11:00:00Z" }, now)).toBe(true);
    expect(isMuted({ muted: true, mutedUntil: "2026-11-26T09:00:00Z" }, now)).toBe(false);
  });
});

describe("inbox rows", () => {
  it("never invents text for deleted or hidden last messages", () => {
    const last = (status: string, preview?: string, type = "TEXT") => conversation({ id: "c", lastMessage: { messageId: "m", messageType: type, status, preview } });
    expect(lastMessageState(conversation({ id: "c" }))).toBe("none");
    expect(lastMessageState(last("SENT", "hi"))).toBe("text");
    expect(lastMessageState(last("DELETED"))).toBe("deleted");
    expect(lastMessageState(last("HIDDEN", "leaked?"))).toBe("hidden");
    expect(lastMessageState(last("SENT", undefined, "MEDIA"))).toBe("image");
  });

  it("groups a guardian's inbox by child, keeping order and putting the rest last", () => {
    const a1 = conversation({ id: "1", contextStudent: { studentId: "A", displayName: "Abebe" } });
    const b = conversation({ id: "2", contextStudent: { studentId: "B", displayName: "Bethel" } });
    const a2 = conversation({ id: "3", onBehalfOfStudentId: "A" });
    const none = conversation({ id: "4", type: "GROUP" });
    const groups = groupByChild([a1, b, a2, none]);
    expect(groups.map((g) => [g.studentId, g.childName, g.items.map((c) => c.id)])).toEqual([
      ["A", "Abebe", ["1", "3"]],
      ["B", "Bethel", ["2"]],
      [null, null, ["4"]],
    ]);
    expect(shouldGroupByChild([a1])).toBe(true);
    expect(shouldGroupByChild([none])).toBe(false);
  });

  it("merges inbox pages without duplicates", () => {
    const c1 = conversation({ id: "1" });
    const c2 = conversation({ id: "2" });
    expect(flattenInbox([{ items: [c1, c2] }, { items: [c2] }]).map((c) => c.id)).toEqual(["1", "2"]);
  });
});

describe("edit window", () => {
  const sent = "2026-11-26T10:00:00Z";
  it("counts down and closes", () => {
    expect(editSecondsLeft(sent, 15, new Date("2026-11-26T10:03:00Z"))).toBe(12 * 60);
    expect(editSecondsLeft(sent, 15, new Date("2026-11-26T10:15:00Z"))).toBe(0);
    expect(editSecondsLeft(sent, 15, new Date("2026-11-26T11:00:00Z"))).toBe(0);
  });

  it("is off when the window is 0 or the time is unknown", () => {
    expect(editSecondsLeft(sent, 0, new Date("2026-11-26T10:00:01Z"))).toBe(0);
    expect(editSecondsLeft(undefined, 15, new Date())).toBe(0);
    expect(editSecondsLeft("nope", 15, new Date())).toBe(0);
  });

  it("rounds minutes up and needs both the API's flag and time left", () => {
    expect(editMinutesLeft(61)).toBe(2);
    expect(editMinutesLeft(0)).toBe(0);
    const now = new Date("2026-11-26T10:03:00Z");
    expect(canEditNow({ canEdit: true, sentAt: sent }, 15, now)).toBe(true);
    expect(canEditNow({ canEdit: false, sentAt: sent }, 15, now)).toBe(false);
    expect(canEditNow({ canEdit: true, sentAt: sent }, 15, new Date("2026-11-26T10:16:00Z"))).toBe(false);
  });

  it("finds when the next window closes", () => {
    const now = new Date("2026-11-26T10:03:00Z");
    const list = [
      { mine: true, canEdit: true, sentAt: sent },
      { mine: true, canEdit: true, sentAt: "2026-11-26T10:02:00Z" },
      { mine: false, canEdit: true, sentAt: "2026-11-26T10:01:00Z" },
    ];
    expect(nextEditDeadlineMs(list, 15, now)).toBe(12 * 60_000);
    expect(nextEditDeadlineMs([], 15, now)).toBeNull();
  });
});

describe("scheduling in the school's time zone", () => {
  const now = new Date("2026-11-26T10:00:00Z");
  it("reads the picked time as the school's wall clock", () => {
    // 08:00 in Addis Ababa (UTC+3) is 05:00 UTC, whatever zone the browser is in
    expect(checkSchedule("2026-11-27T08:00", "Africa/Addis_Ababa", now)).toEqual({ ok: true, instant: "2026-11-27T05:00:00.000Z" });
    expect(checkSchedule("2026-11-27T08:00", "UTC", now)).toEqual({ ok: true, instant: "2026-11-27T08:00:00.000Z" });
  });

  it("refuses the past, the too near, the too far and nonsense", () => {
    expect(checkSchedule("2026-11-26T12:59", "Africa/Addis_Ababa", now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2026-11-26T13:00", "Africa/Addis_Ababa", now)).toEqual({ ok: false, reason: "past" });
    expect(checkSchedule("2027-06-01T08:00", "UTC", now)).toEqual({ ok: false, reason: "tooFar" });
    expect(checkSchedule("garbage", "UTC", now)).toEqual({ ok: false, reason: "invalid" });
    expect(checkSchedule("2026-11-27T08:00", "Not/AZone", now)).toEqual({ ok: false, reason: "invalid" });
  });

  it("offers bounds and a default on the school's clock", () => {
    expect(scheduleBounds("Africa/Addis_Ababa", now)?.min).toBe("2026-11-26T13:01");
    expect(defaultScheduleLocal("Africa/Addis_Ababa", now)).toBe("2026-11-27T08:00");
    expect(scheduleBounds("Not/AZone", now)).toBeNull();
  });
});

describe("the doorbell", () => {
  it("accepts only well-formed ids-only events", () => {
    const ok = parseDoorbell(JSON.stringify({ type: "message.created", conversationId: ID, messageId: ID2, at: "2026-11-26T10:00:00Z" }));
    expect(ok).toEqual({ type: "message.created", conversationId: ID, messageId: ID2, at: "2026-11-26T10:00:00Z" });
    expect(parseDoorbell(JSON.stringify({ type: "message.updated", conversationId: ID, messageId: ID2 }))?.type).toBe("message.updated");
  });

  it("ignores anything else: other types, bad ids, junk", () => {
    expect(parseDoorbell("not json")).toBeNull();
    expect(parseDoorbell("null")).toBeNull();
    expect(parseDoorbell(JSON.stringify({ type: "message.deleted", conversationId: ID, messageId: ID2 }))).toBeNull();
    expect(parseDoorbell(JSON.stringify({ type: "message.created", conversationId: "../x", messageId: ID2 }))).toBeNull();
    expect(parseDoorbell(JSON.stringify({ type: "message.created", conversationId: ID }))).toBeNull();
  });

  it("never carries content: it only names the queries to refetch", () => {
    const keys = doorbellKeys({ conversationId: ID });
    expect(JSON.stringify(keys)).not.toMatch(/body|text|name/i);
    expect(keys).toContainEqual(["messaging", "messages", ID]);
    expect(keys).toContainEqual(["messaging", "unread"]);
    expect(ALL_MESSAGING_KEYS).toEqual([["messaging"]]);
  });

  it("polls fast while the socket is down, slowly while it is up, never in a hidden tab", () => {
    expect(pollInterval({ socketUp: false, hidden: false })).toBe(15_000);
    expect(pollInterval({ socketUp: true, hidden: false })).toBe(120_000);
    expect(pollInterval({ socketUp: false, hidden: true })).toBe(false);
  });
});

describe("notification deep links", () => {
  it("opens a conversation by a validated id", () => {
    expect(messagingDeepLink(`/messages/${ID}`)).toBe(`/messages/${ID}`);
    expect(resolveDeepLink({ path: `/messages/${ID}` })).toBe(`/messages/${ID}`);
  });

  it("falls back to the inbox for ids that are not UUIDs (never into an API path)", () => {
    expect(messagingDeepLink("/messages/..%2Fadmin")).toBe("/messages");
    expect(messagingDeepLink("/messages/123")).toBe("/messages");
    expect(resolveDeepLink({ path: "/messages/not-a-uuid" })).toBe("/messages");
  });

  it("opens the approvals queue and the reviewers' page", () => {
    expect(resolveDeepLink({ path: "/messages/groups" })).toBe("/messages/groups");
    expect(resolveDeepLink({ path: "/s/sunrise/messaging/review" })).toBe("/s/sunrise/messages/review");
    expect(messagingDeepLink("/s/Bad Slug/messaging/review")).toBeNull();
  });

  it("is not for other paths", () => {
    expect(messagingDeepLink("/announcements")).toBeNull();
  });
});

describe("toasts for the open conversation", () => {
  it("are skipped only for the conversation on screen", () => {
    expect(isAboutOpenConversation({ path: `/messages/${ID}` }, `/en/messages/${ID}`)).toBe(true);
    expect(isAboutOpenConversation({ path: `/messages/${ID}` }, `/en/s/sunrise/messages/${ID}`)).toBe(true);
    expect(isAboutOpenConversation({ path: `/messages/${ID}` }, `/en/messages/${ID2}`)).toBe(false);
    expect(isAboutOpenConversation({ path: `/messages/${ID}` }, "/en/messages")).toBe(false);
    expect(isAboutOpenConversation({ path: "/announcements" }, `/en/messages/${ID}`)).toBe(false);
    expect(isAboutOpenConversation(null, `/en/messages/${ID}`)).toBe(false);
  });
});

describe("review reason", () => {
  it("needs 10 to 300 characters, trimmed", () => {
    expect(checkReason("too short")).toEqual({ ok: false, problem: "short" });
    expect(checkReason("          ")).toEqual({ ok: false, problem: "short" });
    expect(checkReason("  Report from a parent  ")).toEqual({ ok: true, reason: "Report from a parent" });
    expect(checkReason("x".repeat(300)).ok).toBe(true);
    expect(checkReason("x".repeat(301))).toEqual({ ok: false, problem: "long" });
    expect(reasonRemaining("x".repeat(250))).toBe(50);
  });
});

describe("how people are named", () => {
  it("names teachers and students, labels guardians", () => {
    expect(personName({ actingAs: "TEACHER", displayName: "Mr Bekele" })).toEqual({ kind: "name", name: "Mr Bekele" });
    expect(personName({ actingAs: "GUARDIAN", guardian: { relationshipType: "MOTHER", studentGivenName: "Abebe" } })).toEqual({ kind: "guardianOf", relationship: "MOTHER", student: "Abebe" });
    expect(personName({ actingAs: "GUARDIAN", guardian: { relationshipType: "FATHER" } })).toEqual({ kind: "guardianRelation", relationship: "FATHER" });
    expect(personName({ actingAs: "GUARDIAN", guardian: {} })).toEqual({ kind: "guardian" });
    expect(personName(undefined)).toEqual({ kind: "unknown" });
    expect(contactName({ actingAs: "GUARDIAN", guardian: { relationshipType: "MOTHER", studentGivenName: "Abebe" } })).toEqual({ kind: "guardianOf", relationship: "MOTHER", student: "Abebe" });
  });

  it("has initials for names only", () => {
    expect(initialsOf({ kind: "name", name: "abebe kebede" })).toBe("AK");
    expect(initialsOf({ kind: "guardian" })).toBe("");
  });
});
