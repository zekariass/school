import { describe, expect, it } from "vitest";
import {
  ackState,
  acknowledgementCount,
  authoredStatus,
  badgeCount,
  badgeText,
  canAcknowledge,
  canRemind,
  channelState,
  isAnnouncementNotification,
  cardIsNew,
  cardNeedsAcknowledgement,
  csvFileName,
  defaultLanguage,
  languagesOf,
  textIn,
  inboxQuery,
  isInboxFilter,
  isNewToViewer,
  isTombstone,
  isUrgentType,
  mayRequireAcknowledgement,
  needsPublishConfirmation,
  percent,
  recipientFilters,
  schoolTabs,
  waitParts,
} from "./view";

describe("types", () => {
  it("urgent and safety are the urgent ones, an acknowledgement needs more than NORMAL", () => {
    expect(isUrgentType("URGENT")).toBe(true);
    expect(isUrgentType("SAFETY")).toBe(true);
    expect(isUrgentType("IMPORTANT")).toBe(false);
    expect(mayRequireAcknowledgement("NORMAL")).toBe(false);
    expect(mayRequireAcknowledgement("IMPORTANT")).toBe(true);
  });
});

describe("inboxQuery", () => {
  it("leaves the defaults out", () => {
    expect(inboxQuery({})).toEqual({});
    expect(inboxQuery({ filter: "ALL", sort: "NEWEST" })).toEqual({});
  });

  it("sends only what was chosen", () => {
    expect(inboxQuery({ filter: "UNREAD" })).toEqual({ filter: "UNREAD" });
    expect(inboxQuery({ filter: "NEEDS_ACKNOWLEDGEMENT", sort: "UNREAD_FIRST", schoolId: "s1", cursor: "c", limit: 20 })).toEqual({
      schoolId: "s1",
      filter: "NEEDS_ACKNOWLEDGEMENT",
      sort: "UNREAD_FIRST",
      cursor: "c",
      limit: 20,
    });
    expect(inboxQuery({ schoolId: null })).toEqual({});
  });

  it("recognizes only the API's filters", () => {
    expect(isInboxFilter("UNREAD")).toBe(true);
    expect(isInboxFilter("unread")).toBe(false);
    expect(isInboxFilter(undefined)).toBe(false);
  });
});

describe("badges", () => {
  it("count unread announcements, never negative or fractional", () => {
    expect(badgeCount({ unread: 3, needingAcknowledgement: 1 })).toBe(3);
    expect(badgeCount(undefined)).toBe(0);
    expect(badgeCount({ unread: -2 })).toBe(0);
    expect(badgeCount({ unread: 2.7 })).toBe(2);
    expect(acknowledgementCount({ needingAcknowledgement: 4 })).toBe(4);
    expect(acknowledgementCount({})).toBe(0);
  });

  it("cap what they show", () => {
    expect(badgeText(7)).toBe("7");
    expect(badgeText(100)).toBe("99+");
  });
});

describe("acknowledge state", () => {
  const base = { requiresAcknowledgement: true, status: "PUBLISHED" as const, revision: 1, authorId: "author", mine: { acknowledged: false } };

  it("asks once, then is done", () => {
    expect(ackState(base, "me")).toBe("pending");
    expect(canAcknowledge(base, "me")).toBe(true);
    const done = { ...base, mine: { acknowledged: true, acknowledgedRevision: 1 } };
    expect(ackState(done, "me")).toBe("done");
    expect(canAcknowledge(done, "me")).toBe(false);
  });

  it("asks again only after a material edit, as the server says", () => {
    const material = { ...base, revision: 3, mine: { acknowledged: true, acknowledgedRevision: 2, reacknowledgementNeeded: true } };
    expect(ackState(material, "me")).toBe("again");
    expect(canAcknowledge(material, "me")).toBe(true);
    // acknowledging the current revision settles it
    expect(ackState({ ...material, mine: { acknowledged: true, acknowledgedRevision: 3, reacknowledgementNeeded: false } }, "me")).toBe("done");
  });

  it("does not ask again after a silent correction even though the revision moved", () => {
    const silent = { ...base, revision: 3, mine: { acknowledged: true, acknowledgedRevision: 1, reacknowledgementNeeded: false } };
    expect(ackState(silent, "me")).toBe("done");
    expect(canAcknowledge(silent, "me")).toBe(false);
  });

  it("treats an acknowledgement without a flag as current", () => {
    expect(ackState({ ...base, revision: 5, mine: { acknowledged: true } }, "me")).toBe("done");
    expect(ackState({ ...base, revision: 5, mine: { acknowledged: true, acknowledgedRevision: null } }, "me")).toBe("done");
  });

  it("has nothing to acknowledge when it is not asked for, withdrawn, a draft or one's own", () => {
    expect(ackState({ ...base, requiresAcknowledgement: false }, "me")).toBe("none");
    expect(ackState({ ...base, status: "WITHDRAWN" }, "me")).toBe("none");
    expect(ackState({ ...base, status: "DRAFT" }, "me")).toBe("none");
    expect(ackState(base, "author")).toBe("none");
    expect(ackState({ ...base, mine: undefined }, "me")).toBe("pending");
  });

  it("a list card knows only whether the viewer acknowledged", () => {
    expect(cardNeedsAcknowledgement({ requiresAcknowledgement: true, status: "PUBLISHED", acknowledgedAt: null })).toBe(true);
    expect(cardNeedsAcknowledgement({ requiresAcknowledgement: true, status: "PUBLISHED", acknowledgedAt: "2026-11-26T10:00:00Z" })).toBe(false);
    expect(cardNeedsAcknowledgement({ requiresAcknowledgement: true, status: "WITHDRAWN" })).toBe(false);
    expect(cardNeedsAcknowledgement({ requiresAcknowledgement: false, status: "PUBLISHED" })).toBe(false);
  });
});

describe("new badge", () => {
  it("is for unread announcements the viewer was addressed by", () => {
    expect(isNewToViewer({ status: "PUBLISHED", mine: { recipient: true, read: false } })).toBe(true);
    expect(isNewToViewer({ status: "PUBLISHED", mine: { recipient: true, read: true } })).toBe(false);
  });

  it("is never shown to a late joiner (not in the recipient snapshot)", () => {
    expect(isNewToViewer({ status: "PUBLISHED", mine: { recipient: false, read: false } })).toBe(false);
  });

  it("is not shown for withdrawn announcements or without knowing the viewer's state", () => {
    expect(isNewToViewer({ status: "WITHDRAWN", mine: { recipient: true, read: false } })).toBe(false);
    expect(isNewToViewer({ status: "PUBLISHED" })).toBe(false);
    expect(cardIsNew({ status: "PUBLISHED", readAt: null })).toBe(true);
    expect(cardIsNew({ status: "PUBLISHED", readAt: "2026-11-26T10:00:00Z" })).toBe(false);
    expect(cardIsNew({ status: "WITHDRAWN" })).toBe(false);
  });

  it("a withdrawn announcement is a tombstone", () => {
    expect(isTombstone({ status: "WITHDRAWN" })).toBe(true);
    expect(isTombstone({ status: "PUBLISHED" })).toBe(false);
  });
});

describe("tabs and statuses", () => {
  it("shows the tabs the caller can use", () => {
    expect(schoolTabs({ canCompose: false, manager: false })).toEqual(["school"]);
    expect(schoolTabs({ canCompose: true, manager: false })).toEqual(["school", "mine"]);
    expect(schoolTabs({ canCompose: false, manager: true })).toEqual(["school", "mine", "all"]);
  });

  it("maps the status tab to the API's filter", () => {
    expect(authoredStatus("ALL")).toBeUndefined();
    expect(authoredStatus("DRAFT")).toBe("DRAFT");
  });
});

describe("report helpers", () => {
  it("offers the acknowledgement filters only when one is asked for", () => {
    expect(recipientFilters(false)).toEqual(["ALL", "UNREAD", "FAILED"]);
    expect(recipientFilters(true)).toEqual(["ALL", "UNREAD", "UNACKNOWLEDGED", "ACKNOWLEDGED", "FAILED"]);
  });

  it("names the CSV without the title", () => {
    expect(csvFileName("0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70", new Date("2026-11-26T10:00:00Z"))).toBe("announcement-0198b3a2-2026-11-26.csv");
  });

  it("computes meters safely", () => {
    expect(percent(5, 10)).toBe(50);
    expect(percent(1, 3)).toBe(33);
    expect(percent(5, 0)).toBe(0);
    expect(percent(-1, 5)).toBe(0);
    expect(percent(9, 3)).toBe(100);
  });

  it("splits a wait into hours and minutes", () => {
    expect(waitParts(7500)).toEqual({ hours: 2, minutes: 5 });
    expect(waitParts(30)).toEqual({ hours: 0, minutes: 1 });
    expect(waitParts(0)).toEqual({ hours: 0, minutes: 0 });
  });

  it("offers the reminder only for a published announcement that asks for an acknowledgement and the API allows it", () => {
    const a = { status: "PUBLISHED" as const, requiresAcknowledgement: true, can: { remind: true } };
    expect(canRemind(a)).toBe(true);
    expect(canRemind({ ...a, can: { remind: false } })).toBe(false);
    expect(canRemind({ ...a, requiresAcknowledgement: false })).toBe(false);
    expect(canRemind({ ...a, status: "WITHDRAWN" })).toBe(false);
  });
});

describe("publish confirmation", () => {
  it("asks for large audiences and for urgent and safety messages", () => {
    expect(needsPublishConfirmation(5, "NORMAL")).toBe(false);
    expect(needsPublishConfirmation(25, "NORMAL")).toBe(true);
    expect(needsPublishConfirmation(1, "URGENT")).toBe(true);
    expect(needsPublishConfirmation(1, "SAFETY")).toBe(true);
  });
});

describe("channel states", () => {
  it("names what the report says", () => {
    expect(channelState("SENT")).toBe("sent");
    expect(channelState("FAILED")).toBe("failed");
    expect(channelState("PENDING")).toBe("waiting");
    expect(channelState("SENDING")).toBe("waiting");
    expect(channelState("HELD")).toBe("held");
    expect(channelState("SUPPRESSED")).toBe("suppressed");
    expect(channelState("SKIPPED")).toBe("skipped");
    expect(channelState("SKIPPED_opted_out")).toBe("skipped");
    expect(channelState("NOT_REQUESTED")).toBe("none");
    expect(channelState("NONE")).toBe("none");
    expect(channelState(undefined)).toBe("none");
    expect(channelState("something new")).toBe("none");
  });
});

describe("announcement notifications", () => {
  it("are recognised by the path the API sends", () => {
    expect(isAnnouncementNotification({ path: "/announcements/0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f70" })).toBe(true);
    expect(isAnnouncementNotification({ path: "/announcements" })).toBe(true);
    expect(isAnnouncementNotification({ path: "/announcementsx" })).toBe(false);
    expect(isAnnouncementNotification({ path: "/feed/posts/1" })).toBe(false);
    expect(isAnnouncementNotification(null)).toBe(false);
    expect(isAnnouncementNotification({ path: 3 })).toBe(false);
  });
});

describe("reading in a language", () => {
  const a = { title: "Trip", body: "We go on Friday.", bodyLanguageCode: "en", translations: { am: { title: "ጉዞ", body: "አርብ እንሄዳለን።" } } };

  it("lists the original first, then the translations people wrote", () => {
    expect(languagesOf(a)).toEqual(["en", "am"]);
    expect(languagesOf({})).toEqual(["und"]);
    expect(languagesOf({ bodyLanguageCode: "en", translations: { en: { title: "a", body: "b" } } })).toEqual(["en"]);
  });

  it("opens in the reader's language only when it was translated, otherwise in the original", () => {
    expect(defaultLanguage(a, "am")).toBe("am");
    expect(defaultLanguage(a, "en")).toBe("en");
    expect(defaultLanguage(a, "fr")).toBe("en");
    expect(defaultLanguage({ ...a, translations: {} }, "am")).toBe("en");
    expect(defaultLanguage({ ...a, translations: undefined }, "am")).toBe("en");
  });

  it("gives the text in the language asked for, else the original", () => {
    expect(textIn(a, "am")).toEqual({ title: "ጉዞ", body: "አርብ እንሄዳለን።", language: "am" });
    expect(textIn(a, "en")).toEqual({ title: "Trip", body: "We go on Friday.", language: "en" });
    expect(textIn(a, "fr")).toEqual({ title: "Trip", body: "We go on Friday.", language: "en" });
    expect(textIn(a, "constructor")).toEqual({ title: "Trip", body: "We go on Friday.", language: "en" });
  });

  it("has no body for a withdrawn announcement", () => {
    expect(textIn({ title: "Trip", bodyLanguageCode: "en" }, "en").body).toBeNull();
  });
});
