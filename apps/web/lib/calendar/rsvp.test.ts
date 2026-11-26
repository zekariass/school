import { describe, expect, it } from "vitest";
import { canCreateInSchool, classChoice, defaultTypeForScope, schoolScopes, scopeAllowsAllDay, scopeAllowsRecurrence, scopeOffersRsvp, typesForScope, type EventCapabilities } from "./access";
import { actionsOf, choicesFor, isParentMeeting, myAnswer, participantsOf, rsvpMode, totalsOf, type EventView } from "./rsvp";

const base: EventView = { id: "e", eventType: "SCHOOL_EVENT", scope: "SCHOOL", status: "SCHEDULED", timeZone: "UTC", title: "t" };

describe("rsvpMode", () => {
  it("shows nothing for events that do not ask for answers", () => {
    expect(rsvpMode(base)).toBe("none");
    expect(rsvpMode({ ...base, rsvpEnabled: false, rsvp: { enabled: false } })).toBe("none");
  });

  it("lets people answer while the API says they can", () => {
    expect(rsvpMode({ ...base, rsvpEnabled: true, rsvp: { enabled: true, mine: "PENDING", canAnswer: true } })).toBe("answerable");
    expect(rsvpMode({ ...base, rsvpEnabled: true, rsvp: { enabled: true, mine: "YES", canAnswer: true } })).toBe("answerable");
  });

  it("closes the buttons after the start or a cancellation, keeping the answer visible", () => {
    expect(rsvpMode({ ...base, rsvpEnabled: true, rsvp: { enabled: true, mine: "YES", canAnswer: false } })).toBe("closed");
    expect(rsvpMode({ ...base, status: "CANCELLED", rsvpEnabled: true, rsvp: { enabled: true, mine: "YES", canAnswer: true } })).toBe("closed");
  });

  it("treats people who were not asked (managers, organisers) as observers", () => {
    expect(rsvpMode({ ...base, rsvpEnabled: true, rsvp: { enabled: true, canAnswer: false, counts: { yes: 1 } } })).toBe("observer");
  });

  it("explains that repeating events take no answers, whatever else the event says", () => {
    expect(rsvpMode({ ...base, recurring: true })).toBe("recurring");
    expect(rsvpMode({ ...base, recurring: true, rsvpEnabled: true, rsvp: { enabled: true, mine: "PENDING", canAnswer: true } })).toBe("recurring");
  });
});

describe("answers and totals", () => {
  it("reads the caller's real answer only", () => {
    expect(myAnswer({ rsvp: { mine: "MAYBE" } })).toBe("MAYBE");
    expect(myAnswer({ rsvp: { mine: "PENDING" } })).toBeNull();
    expect(myAnswer({})).toBeNull();
  });

  it("sums the totals the API sent, and shows none when it sent none", () => {
    expect(totalsOf({ rsvp: { counts: { yes: 3, no: 1, maybe: 2, pending: 4 } } })).toEqual({ yes: 3, no: 1, maybe: 2, pending: 4, total: 10 });
    expect(totalsOf({ rsvp: { counts: {} } })).toEqual({ yes: 0, no: 0, maybe: 0, pending: 0, total: 0 });
    expect(totalsOf({ rsvp: { enabled: true } })).toBeNull();
    expect(totalsOf({})).toBeNull();
  });

  it("lists who answered what only when the API sent the list", () => {
    expect(participantsOf({ rsvp: { enabled: true, counts: { yes: 1 } } })).toBeNull();
    expect(
      participantsOf({
        rsvp: {
          participants: [
            { displayName: "Almaz", status: "YES", respondedAt: "2026-12-01T10:00:00Z" },
            { displayName: "Bekele", status: "PENDING" },
            { displayName: "Chaltu", status: "weird" },
          ],
        },
      }),
    ).toEqual([
      { name: "Almaz", status: "YES", respondedAt: "2026-12-01T10:00:00Z" },
      { name: "Bekele", status: "PENDING", respondedAt: null },
      { name: "Chaltu", status: "UNKNOWN", respondedAt: null },
    ]);
  });

  it("recognises parent meetings", () => {
    expect(isParentMeeting({ eventType: "PARENT_MEETING" })).toBe(true);
    expect(isParentMeeting({ eventType: "EXAM" })).toBe(false);
  });
});

describe("actions and choices", () => {
  it("offers what the capabilities say, minus what a cancelled event cannot do", () => {
    expect(actionsOf({ status: "SCHEDULED", capabilities: { canEdit: true, canCancel: true, canDelete: false } })).toEqual({ edit: true, cancel: true, delete: false });
    expect(actionsOf({ status: "CANCELLED", capabilities: { canEdit: true, canCancel: true, canDelete: true } })).toEqual({ edit: false, cancel: false, delete: true });
    expect(actionsOf({ status: "SCHEDULED" })).toEqual({ edit: false, cancel: false, delete: false });
  });

  it("offers 'only this event' for a series opened at an occurrence, the series otherwise", () => {
    expect(choicesFor({ recurring: true }, true)).toEqual(["occurrence", "series"]);
    expect(choicesFor({ recurring: true }, false)).toEqual(["series"]);
    expect(choicesFor({ recurring: false }, true)).toEqual(["series"]);
  });
});

describe("composer capabilities", () => {
  const manager: EventCapabilities = { calendarEnabled: true, canManage: true, canCreateSchoolEvents: true, canCreateAnyClassEvent: true, canCreateParentMeetings: false };
  const teacher: EventCapabilities = { calendarEnabled: true, canCreateParentMeetings: true, classes: [{ id: "c1", name: "5A" }] };

  it("offers school-wide scopes, classes and meetings per capability", () => {
    expect(schoolScopes(manager)).toEqual(["SCHOOL", "CAMPUS", "GRADE_LEVEL", "CLASS"]);
    expect(schoolScopes(teacher)).toEqual(["CLASS", "PARENT_MEETING"]);
    expect(schoolScopes({ calendarEnabled: true })).toEqual([]);
    expect(schoolScopes({ ...manager, calendarEnabled: false })).toEqual([]);
    expect(schoolScopes(null)).toEqual([]);
    expect(canCreateInSchool(teacher)).toBe(true);
    expect(canCreateInSchool({ calendarEnabled: true })).toBe(false);
  });

  it("lets managers pick any class and teachers only theirs", () => {
    expect(classChoice(manager)).toEqual({ all: true });
    expect(classChoice(teacher)).toEqual({ all: false, classes: [{ id: "c1", name: "5A" }] });
    expect(classChoice({ canCreateAnyClassEvent: true, classes: [{ id: "c2", name: "6B" }] })).toEqual({ all: false, classes: [{ id: "c2", name: "6B" }] });
  });

  it("limits the types to the scope", () => {
    expect(typesForScope("SCHOOL")).toEqual(["SCHOOL_EVENT", "EXAM", "OTHER"]);
    expect(typesForScope("GRADE_LEVEL")).toEqual(["SCHOOL_EVENT", "EXAM", "OTHER"]);
    expect(typesForScope("CLASS")).toEqual(["CLASS_EVENT", "EXAM", "OTHER"]);
    expect(typesForScope("PARENT_MEETING")).toEqual(["PARENT_MEETING"]);
    expect(typesForScope("PERSONAL")).toEqual(["OTHER"]);
    expect(defaultTypeForScope("CLASS")).toBe("CLASS_EVENT");
  });

  it("knows which controls a scope has", () => {
    expect(scopeAllowsRecurrence("PARENT_MEETING")).toBe(false);
    expect(scopeAllowsAllDay("PARENT_MEETING")).toBe(false);
    expect(scopeOffersRsvp("PARENT_MEETING")).toBe(false);
    expect(scopeOffersRsvp("PERSONAL")).toBe(false);
    expect(scopeOffersRsvp("CLASS")).toBe(true);
    expect(scopeAllowsRecurrence("PERSONAL")).toBe(true);
  });
});
