import { describe, expect, it } from "vitest";
import { conversation, message } from "./fixtures";
import { banners, dayKey, findReplyTarget, flattenMessages, hasReceipts, isTombstone, lastSeenId, newestForeignId, readOnlyReason, threadRows, tombstone, upsertMessage } from "./thread";

describe("merging and ordering pages", () => {
  const m1 = message({ id: "m1", sentAt: "2026-11-26T08:00:00Z" });
  const m2 = message({ id: "m2", sentAt: "2026-11-26T08:01:00Z" });
  const m3 = message({ id: "m3", sentAt: "2026-11-26T08:02:00Z" });

  it("shows the oldest first although the API pages newest first", () => {
    const pages = [{ items: [m3, m2] }, { items: [m1] }];
    expect(flattenMessages(pages).map((m) => m.id)).toEqual(["m1", "m2", "m3"]);
  });

  it("drops a message that appears in two pages, keeping the fresher page's copy", () => {
    const stale = { ...m2, body: "old" };
    const pages = [{ items: [m3, m2] }, { items: [stale, m1] }];
    const flat = flattenMessages(pages);
    expect(flat.map((m) => m.id)).toEqual(["m1", "m2", "m3"]);
    expect(flat[1]!.body).toBe("hello");
  });

  it("keeps the API order for equal instants", () => {
    const a = message({ id: "a", sentAt: "2026-11-26T08:00:00Z" });
    const b = message({ id: "b", sentAt: "2026-11-26T08:00:00Z" });
    // newest first: b was sent after a
    expect(flattenMessages([{ items: [b, a] }]).map((m) => m.id)).toEqual(["a", "b"]);
  });

  it("upserts a sent message into the newest page once", () => {
    const pages = [{ items: [m2], nextCursor: "c" }, { items: [m1] }];
    const sent = message({ id: "m3", mine: true });
    const once = upsertMessage(pages, sent);
    expect(once[0]!.items.map((m) => m.id)).toEqual(["m3", "m2"]);
    expect(once[0]!.nextCursor).toBe("c");
    expect(upsertMessage(once, sent)[0]!.items.map((m) => m.id)).toEqual(["m3", "m2"]);
  });

  it("replaces an edited message in whatever page it is", () => {
    const pages = [{ items: [m3] }, { items: [m2, m1] }];
    const edited = { ...m2, body: "changed" };
    const next = upsertMessage(pages, edited);
    expect(next[1]!.items[0]!.body).toBe("changed");
    expect(next[0]!.items).toHaveLength(1);
  });

  it("makes a tombstone without text, images or reactions", () => {
    const t = tombstone(message({ id: "x", mine: true, canEdit: true, canDelete: true, reactions: [{ emoji: "👍", count: 1, mine: false }], attachments: [{ mediaId: "i", sortOrder: 0 }] }));
    expect(t).toMatchObject({ status: "DELETED", canEdit: false, canDelete: false, attachments: [], reactions: [] });
    expect(t.body).toBeUndefined();
    expect(isTombstone(t)).toBe(true);
    expect(isTombstone(message({ id: "h", status: "HIDDEN" }))).toBe(true);
    expect(isTombstone(message({ id: "s" }))).toBe(false);
  });

  it("finds the newest message of somebody else for marking read, skipping tombstones and own messages", () => {
    const list = [message({ id: "a" }), message({ id: "b", status: "DELETED" }), message({ id: "c", mine: true })];
    expect(newestForeignId(list)).toBe("a");
    expect(newestForeignId([message({ id: "c", mine: true })])).toBeNull();
  });

  it("finds the reply target only among loaded messages", () => {
    expect(findReplyTarget([m1, m2], "m2")).toBe(m2);
    expect(findReplyTarget([m1], "zzz")).toBeNull();
    expect(findReplyTarget([m1], undefined)).toBeNull();
  });
});

describe("date lines and grouping", () => {
  it("puts a date line before each day on the school's clock, not the browser's", () => {
    // 21:30 UTC is already the next day in Addis Ababa (UTC+3)
    expect(dayKey("2026-11-26T21:30:00Z", "Africa/Addis_Ababa")).toBe("2026-11-27");
    expect(dayKey("2026-11-26T21:30:00Z", "UTC")).toBe("2026-11-26");
    const rows = threadRows([message({ id: "a", sentAt: "2026-11-26T20:00:00Z" }), message({ id: "b", sentAt: "2026-11-26T21:30:00Z" })], "Africa/Addis_Ababa");
    expect(rows.map((r) => r.kind)).toEqual(["day", "message", "day", "message"]);
  });

  it("groups consecutive messages of the same sender within five minutes", () => {
    const sender = { memberId: "p1", actingAs: "TEACHER", role: "MEMBER", self: false } as const;
    const rows = threadRows(
      [
        message({ id: "a", sender, sentAt: "2026-11-26T08:00:00Z" }),
        message({ id: "b", sender, sentAt: "2026-11-26T08:02:00Z" }),
        message({ id: "c", sender, sentAt: "2026-11-26T08:20:00Z" }),
      ],
      "UTC",
    );
    const grouped = rows.filter((r) => r.kind === "message").map((r) => (r.kind === "message" ? r.grouped : null));
    expect(grouped).toEqual([false, true, false]);
  });
});

describe("tombstones, observers and read-only state", () => {
  it("turns the composer off for observers whatever the API says, then for closed conversations, then for restricted posting", () => {
    expect(readOnlyReason(conversation({ id: "c", myRole: "OBSERVER", canPost: true }))).toBe("observer");
    expect(readOnlyReason(conversation({ id: "c", status: "LOCKED" }))).toBe("closed");
    expect(readOnlyReason(conversation({ id: "c", status: "ARCHIVED" }))).toBe("closed");
    expect(readOnlyReason(conversation({ id: "c", canPost: false, postingPolicy: "MODERATORS_ONLY" }))).toBe("restricted");
    expect(readOnlyReason(conversation({ id: "c" }))).toBeNull();
  });

  it("tells an observing guardian they only read", () => {
    expect(banners(conversation({ id: "c", myRole: "OBSERVER", myActingAs: "GUARDIAN", directKind: "TEACHER_STUDENT" }))).toEqual([{ kind: "observerGuardian" }]);
  });

  it("tells a student who can read their conversation with an adult", () => {
    const student = conversation({ id: "c", myActingAs: "STUDENT", directKind: "TEACHER_STUDENT", guardiansCanSee: true });
    expect(banners(student)).toEqual([{ kind: "studentGuardiansCanSee" }]);
    expect(banners({ ...student, guardiansCanSee: false })).toEqual([{ kind: "studentReviewable" }]);
  });

  it("tells the adult the conversation with a student is not private", () => {
    expect(banners(conversation({ id: "c", myActingAs: "TEACHER", directKind: "TEACHER_STUDENT" }))).toEqual([{ kind: "adultStudentNotPrivate" }]);
  });

  it("explains closed, pending and restricted conversations", () => {
    expect(banners(conversation({ id: "c", type: "GROUP", status: "LOCKED" }))).toEqual([{ kind: "closed", status: "LOCKED" }]);
    expect(banners(conversation({ id: "c", type: "GROUP", status: "PENDING_APPROVAL" }))).toEqual([{ kind: "pendingApproval" }]);
    expect(banners(conversation({ id: "c", type: "GROUP", canPost: false }))).toEqual([{ kind: "restricted" }]);
    expect(banners(conversation({ id: "c" }))).toEqual([]);
  });

  it("shows 'Seen' only in a direct conversation between non-observers, only on the newest seen own message", () => {
    const list = [message({ id: "a", mine: true, seenAt: "2026-11-26T08:05:00Z" }), message({ id: "b", mine: true }), message({ id: "x", seenAt: "2026-11-26T08:05:00Z" })];
    expect(lastSeenId(list, true)).toBe("a");
    expect(lastSeenId(list, false)).toBeNull();
    expect(hasReceipts(conversation({ id: "c", type: "DIRECT" }))).toBe(true);
    expect(hasReceipts(conversation({ id: "c", type: "DIRECT", myRole: "OBSERVER" }))).toBe(false);
    expect(hasReceipts(conversation({ id: "c", type: "GROUP" }))).toBe(false);
  });
});
