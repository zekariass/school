import { describe, expect, it } from "vitest";
import {
  buildCreateBody,
  buildOccurrenceBody,
  buildUpdateBody,
  formFromEvent,
  formProblems,
  initialForm,
  isEmptyUpdate,
  isValidTimeZone,
  occurrenceForm,
  occurrenceProblems,
  problemFor,
  scheduleChanged,
  updateWarnings,
  withAllDay,
  withScope,
  withStart,
  type EventFormState,
  type OccurrenceValues,
} from "./form";
import { noRecurrence } from "./recurrence";
import type { EventView } from "./rsvp";

const NOW = new Date("2026-12-01T09:00:00Z");
const ADDIS = "Africa/Addis_Ababa";
const SCHOOL = "7d6f0f0c-6f0e-4d6b-8c52-0a2f3a1e9b10";
const CLASS = "8a1f0f0c-6f0e-4d6b-8c52-0a2f3a1e9b11";
const STUDENT = "9b2f0f0c-6f0e-4d6b-8c52-0a2f3a1e9b12";

function form(patch: Partial<EventFormState> = {}): EventFormState {
  return { ...initialForm({ scope: "SCHOOL", schoolId: SCHOOL, timeZone: ADDIS, now: NOW, day: "2026-12-07" }), title: "Assembly", ...patch };
}

describe("initialForm", () => {
  it("starts tomorrow 08:00 to 09:00 on the zone's clock, with the zone explicit", () => {
    const state = initialForm({ scope: "SCHOOL", schoolId: SCHOOL, timeZone: ADDIS, now: NOW });
    expect(state).toMatchObject({ startLocal: "2026-12-02T08:00", endLocal: "2026-12-02T09:00", timeZone: ADDIS, allDay: false, eventType: "SCHOOL_EVENT" });
    // 23:00 UTC is already tomorrow in Addis, but not in New York
    const late = new Date("2026-12-01T23:00:00Z");
    expect(initialForm({ scope: "SCHOOL", schoolId: SCHOOL, timeZone: ADDIS, now: late }).startLocal).toBe("2026-12-03T08:00");
    expect(initialForm({ scope: "SCHOOL", schoolId: SCHOOL, timeZone: "America/New_York", now: late }).startLocal).toBe("2026-12-02T08:00");
  });

  it("starts on the day that was clicked", () => {
    expect(form().startLocal).toBe("2026-12-07T08:00");
    expect(initialForm({ scope: "PERSONAL", schoolId: SCHOOL, timeZone: ADDIS, now: NOW, day: "bad" }).startDate).toBe("2026-12-02");
  });

  it("drops the school for personal events", () => {
    expect(initialForm({ scope: "PERSONAL", schoolId: SCHOOL, timeZone: ADDIS, now: NOW }).schoolId).toBeNull();
  });
});

describe("scope and kind changes", () => {
  it("resets what depends on the scope", () => {
    const state = form({ scope: "CLASS", classId: CLASS, eventType: "EXAM", recurrence: { ...noRecurrence(), freq: "DAILY" }, rsvpEnabled: true });
    const meeting = withScope(state, "PARENT_MEETING");
    expect(meeting).toMatchObject({ scope: "PARENT_MEETING", eventType: "PARENT_MEETING", classId: "", allDay: false, rsvpEnabled: false });
    expect(meeting.recurrence.freq).toBe("NONE");
    expect(withScope(state, "SCHOOL").eventType).toBe("EXAM");
    expect(withScope(state, "SCHOOL").classId).toBe("");
    expect(withScope(form({ eventType: "SCHOOL_EVENT" }), "CLASS").eventType).toBe("CLASS_EVENT");
  });

  it("keeps the day when switching between timed and all-day", () => {
    const timed = form({ startLocal: "2026-12-07T08:00", endLocal: "2026-12-09T09:00" });
    const day = withAllDay(timed, true);
    expect(day).toMatchObject({ allDay: true, startDate: "2026-12-07", endDate: "2026-12-09" });
    const back = withAllDay(day, false);
    expect(back).toMatchObject({ allDay: false, startLocal: "2026-12-07T08:00", endLocal: "2026-12-09T09:00" });
  });
});

describe("withStart", () => {
  it("moves the end with the start, keeping the length", () => {
    const state = form({ startLocal: "2026-12-07T08:00", endLocal: "2026-12-07T09:30" });
    expect(withStart(state, "2026-12-08T14:00")).toMatchObject({ startLocal: "2026-12-08T14:00", endLocal: "2026-12-08T15:30" });
  });

  it("leaves the end alone when a time is not valid", () => {
    const state = form({ startLocal: "2026-12-07T08:00", endLocal: "2026-12-07T09:30" });
    expect(withStart(state, "").endLocal).toBe("2026-12-07T09:30");
    expect(withStart(form({ endLocal: "" }), "2026-12-08T10:00").endLocal).toBe("");
  });

  it("works across a daylight-saving change by exact length", () => {
    // New York: 10:00 to 11:00 on the 7th; moving to the day of the spring change keeps one hour
    const state = form({ timeZone: "America/New_York", startLocal: "2026-03-07T10:00", endLocal: "2026-03-07T11:00" });
    expect(withStart(state, "2026-03-08T10:00").endLocal).toBe("2026-03-08T11:00");
  });
});

describe("formProblems", () => {
  it("accepts a good form", () => {
    expect(formProblems(form(), NOW)).toEqual([]);
  });

  it("checks the text", () => {
    expect(problemFor(formProblems(form({ title: "  " }), NOW), "title")).toBe("titleRequired");
    expect(problemFor(formProblems(form({ title: "x".repeat(201) }), NOW), "title")).toBe("titleTooLong");
    expect(problemFor(formProblems(form({ title: "x".repeat(200) }), NOW), "title")).toBeNull();
    expect(problemFor(formProblems(form({ description: "x".repeat(5001) }), NOW), "description")).toBe("descriptionTooLong");
    expect(problemFor(formProblems(form({ location: "x".repeat(201) }), NOW), "location")).toBe("locationTooLong");
  });

  it("needs the ids of the chosen scope", () => {
    expect(problemFor(formProblems(form({ scope: "CAMPUS" }), NOW), "campus")).toBe("campusRequired");
    expect(problemFor(formProblems(form({ scope: "GRADE_LEVEL" }), NOW), "grade")).toBe("gradeRequired");
    expect(problemFor(formProblems(form({ scope: "CLASS" }), NOW), "class")).toBe("classRequired");
    expect(problemFor(formProblems(form({ scope: "PARENT_MEETING", eventType: "PARENT_MEETING" }), NOW), "student")).toBe("studentRequired");
    expect(formProblems(form({ scope: "CLASS", classId: CLASS }), NOW)).toEqual([]);
  });

  it("checks the time zone: an IANA name, never an offset", () => {
    expect(isValidTimeZone("Africa/Addis_Ababa")).toBe(true);
    expect(isValidTimeZone("UTC")).toBe(true);
    expect(isValidTimeZone("America/Argentina/Buenos_Aires")).toBe(true);
    expect(isValidTimeZone("+03:00")).toBe(false);
    expect(isValidTimeZone("Not/AZone")).toBe(false);
    expect(isValidTimeZone("")).toBe(false);
    expect(problemFor(formProblems(form({ timeZone: "+03:00" }), NOW), "timeZone")).toBe("timeZoneInvalid");
  });

  it("checks timed events: valid times, the end not before the start, under a year", () => {
    expect(problemFor(formProblems(form({ startLocal: "" }), NOW), "start")).toBe("startInvalid");
    expect(problemFor(formProblems(form({ endLocal: "2026-12-07" }), NOW), "end")).toBe("endInvalid");
    expect(problemFor(formProblems(form({ startLocal: "2026-12-07T10:00", endLocal: "2026-12-07T09:00" }), NOW), "end")).toBe("endBeforeStart");
    expect(problemFor(formProblems(form({ startLocal: "2026-12-07T10:00", endLocal: "2026-12-07T10:00" }), NOW), "end")).toBeNull();
    expect(problemFor(formProblems(form({ startLocal: "2026-12-07T10:00", endLocal: "2027-12-08T10:00" }), NOW), "end")).toBe("tooLong");
  });

  it("refuses a wall-clock time that does not exist in the zone (a daylight-saving gap)", () => {
    // 2026-03-08 02:30 does not exist in New York
    const gap = form({ timeZone: "America/New_York", startLocal: "2026-03-08T02:30", endLocal: "2026-03-08T03:30" });
    expect(problemFor(formProblems(gap, NOW), "start")).toBe("startInvalid");
  });

  it("checks all-day events by dates", () => {
    const day = (patch: Partial<EventFormState>) => form({ allDay: true, startDate: "2026-12-07", endDate: "2026-12-09", ...patch });
    expect(formProblems(day({}), NOW)).toEqual([]);
    expect(problemFor(formProblems(day({ endDate: "2026-12-06" }), NOW), "end")).toBe("endBeforeStart");
    expect(problemFor(formProblems(day({ startDate: "" }), NOW), "start")).toBe("startInvalid");
    expect(problemFor(formProblems(day({ startDate: "2026-02-30" }), NOW), "start")).toBe("startInvalid");
    expect(problemFor(formProblems(day({ endDate: "2027-12-09" }), NOW), "end")).toBe("tooLong");
    // a year minus a day is the longest span
    expect(problemFor(formProblems(day({ startDate: "2026-01-01", endDate: "2026-12-31" }), NOW), "end")).toBeNull();
  });

  it("keeps parent meetings in the future", () => {
    const meeting = (startLocal: string, endLocal: string) => form({ scope: "PARENT_MEETING", eventType: "PARENT_MEETING", studentId: STUDENT, startLocal, endLocal });
    expect(problemFor(formProblems(meeting("2026-12-01T10:00", "2026-12-01T11:00"), NOW), "start")).toBe("meetingInPast");
    // 09:00 UTC = 12:00 in Addis: 11:59 is past, 12:01 is not
    expect(problemFor(formProblems(meeting("2026-12-01T11:59", "2026-12-01T13:00"), NOW), "start")).toBe("meetingInPast");
    expect(formProblems(meeting("2026-12-01T12:01", "2026-12-01T13:00"), NOW)).toEqual([]);
  });

  it("checks the repeat rule and refuses answers on repeating events", () => {
    const weekly = { ...noRecurrence(), freq: "WEEKLY" as const };
    expect(problemFor(formProblems(form({ recurrence: { ...weekly, interval: 0 } }), NOW), "recurrence")).toBe("intervalRange");
    expect(problemFor(formProblems(form({ recurrence: { ...weekly, end: "count", count: 2000 } }), NOW), "recurrence")).toBe("countRange");
    expect(problemFor(formProblems(form({ recurrence: weekly, rsvpEnabled: true }), NOW), "rsvp")).toBe("rsvpRecurring");
    expect(problemFor(formProblems(form({ lockedRule: "FREQ=MONTHLY;BYMONTHDAY=1,15", rsvpEnabled: true }), NOW), "rsvp")).toBe("rsvpRecurring");
    expect(formProblems(form({ recurrence: weekly }), NOW)).toEqual([]);
  });
});

describe("buildCreateBody", () => {
  it("builds a timed school event with instants converted from the event's zone", () => {
    expect(buildCreateBody(form({ title: "  Assembly ", location: " Hall ", startLocal: "2026-12-07T09:00", endLocal: "2026-12-07T10:30" }))).toEqual({
      title: "Assembly",
      location: "Hall",
      scope: "SCHOOL",
      eventType: "SCHOOL_EVENT",
      timeZone: ADDIS,
      startAt: "2026-12-07T06:00:00.000Z",
      endAt: "2026-12-07T07:30:00.000Z",
    });
  });

  it("converts the same wall clock to different instants in different zones", () => {
    const ny = buildCreateBody(form({ timeZone: "America/New_York", startLocal: "2026-12-07T09:00", endLocal: "2026-12-07T10:00" }));
    expect(ny.startAt).toBe("2026-12-07T14:00:00.000Z");
    expect(ny.timeZone).toBe("America/New_York");
    // the zone's own daylight saving applies: 09:00 in New York in July is 13:00 UTC
    const july = buildCreateBody(form({ timeZone: "America/New_York", startLocal: "2026-07-07T09:00", endLocal: "2026-07-07T10:00" }));
    expect(july.startAt).toBe("2026-07-07T13:00:00.000Z");
  });

  it("builds an all-day event from dates only, without instants", () => {
    const body = buildCreateBody(form({ allDay: true, startDate: "2026-12-07", endDate: "2026-12-09", startLocal: "2026-12-07T08:00", endLocal: "2026-12-07T09:00" }));
    expect(body).toMatchObject({ allDay: true, startDate: "2026-12-07", endDate: "2026-12-09", timeZone: ADDIS });
    expect(body).not.toHaveProperty("startAt");
    expect(body).not.toHaveProperty("endAt");
  });

  it("names the campus, grade or class of the scope, only that one", () => {
    expect(buildCreateBody(form({ scope: "CAMPUS", campusId: "c", classId: "stale" }))).toMatchObject({ scope: "CAMPUS", campusId: "c" });
    expect(buildCreateBody(form({ scope: "CAMPUS", campusId: "c", classId: "stale" }))).not.toHaveProperty("classId");
    expect(buildCreateBody(form({ scope: "GRADE_LEVEL", gradeLevelId: "g", eventType: "EXAM" }))).toMatchObject({ scope: "GRADE_LEVEL", gradeLevelId: "g", eventType: "EXAM" });
    expect(buildCreateBody(form({ scope: "CLASS", classId: CLASS, eventType: "CLASS_EVENT" }))).toMatchObject({ scope: "CLASS", classId: CLASS, eventType: "CLASS_EVENT" });
  });

  it("builds a parent meeting without a scope, about a student, with no series or switches", () => {
    const body = buildCreateBody(
      withScope(form({ scope: "PARENT_MEETING", studentId: STUDENT, rsvpEnabled: true, startLocal: "2026-12-07T15:00", endLocal: "2026-12-07T15:20" }), "PARENT_MEETING"),
    );
    expect(body).toMatchObject({ eventType: "PARENT_MEETING", regardingStudentId: STUDENT, timeZone: ADDIS });
    expect(body).not.toHaveProperty("scope");
    expect(body).not.toHaveProperty("rsvpEnabled");
    expect(body).not.toHaveProperty("recurrenceRule");
    expect(body).not.toHaveProperty("allDay");
  });

  it("builds a personal event for /me/events: no scope, no ids, the person's zone", () => {
    const body = buildCreateBody({ ...initialForm({ scope: "PERSONAL", schoolId: null, timeZone: "Europe/Berlin", now: NOW, day: "2026-12-07" }), title: "Dentist" });
    expect(body).toEqual({ title: "Dentist", timeZone: "Europe/Berlin", startAt: "2026-12-07T07:00:00.000Z", endAt: "2026-12-07T08:00:00.000Z" });
  });

  it("adds a repeat rule and the answers switch only where they exist", () => {
    expect(buildCreateBody(form({ recurrence: { ...noRecurrence(), freq: "WEEKLY", byDay: ["WE"], end: "count", count: 8 } })).recurrenceRule).toBe("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=8");
    expect(buildCreateBody(form({ rsvpEnabled: true })).rsvpEnabled).toBe(true);
    expect(buildCreateBody(form({ rsvpEnabled: false }))).not.toHaveProperty("rsvpEnabled");
    // the rule is read from the first day of an all-day event
    const allDay = buildCreateBody(form({ allDay: true, startDate: "2026-12-15", endDate: "2026-12-15", recurrence: { ...noRecurrence(), freq: "MONTHLY" } }));
    expect(allDay.recurrenceRule).toBe("FREQ=MONTHLY;BYMONTHDAY=15");
  });

  it("omits empty optional text", () => {
    const body = buildCreateBody(form({ description: "  ", location: "" }));
    expect(body).not.toHaveProperty("description");
    expect(body).not.toHaveProperty("location");
  });
});

const stored: EventView = {
  id: "e1",
  eventType: "SCHOOL_EVENT",
  scope: "SCHOOL",
  status: "SCHEDULED",
  title: "Assembly",
  description: "Welcome",
  location: "Hall",
  timeZone: ADDIS,
  allDay: false,
  startAt: "2026-12-07T06:00:00Z",
  endAt: "2026-12-07T07:00:00Z",
  schoolId: SCHOOL,
  rsvpEnabled: false,
};

describe("editing an event", () => {
  it("reads the form from the event in the event's own zone", () => {
    expect(formFromEvent(stored)).toMatchObject({ title: "Assembly", description: "Welcome", location: "Hall", startLocal: "2026-12-07T09:00", endLocal: "2026-12-07T10:00", timeZone: ADDIS, scope: "SCHOOL", allDay: false });
    const allDay = formFromEvent({ ...stored, allDay: true, startDate: "2026-12-07", endDate: "2026-12-09", startAt: "2026-12-06T21:00:00Z", endAt: "2026-12-09T21:00:00Z" });
    expect(allDay).toMatchObject({ allDay: true, startDate: "2026-12-07", endDate: "2026-12-09" });
    expect(formFromEvent({ ...stored, eventType: "PARENT_MEETING", scope: "INVITEES", regardingStudentId: STUDENT })).toMatchObject({ scope: "PARENT_MEETING", studentId: STUDENT });
  });

  it("reads a stored rule, or keeps one the builder cannot show", () => {
    expect(formFromEvent({ ...stored, recurring: true, recurrenceRule: "FREQ=WEEKLY;COUNT=3" }).recurrence).toMatchObject({ freq: "WEEKLY", end: "count", count: 3 });
    const locked = formFromEvent({ ...stored, recurring: true, recurrenceRule: "FREQ=MONTHLY;BYMONTHDAY=1,15" });
    expect(locked.lockedRule).toBe("FREQ=MONTHLY;BYMONTHDAY=1,15");
    expect(locked.recurrence.freq).toBe("NONE");
  });

  it("sends nothing when nothing changed", () => {
    const body = buildUpdateBody(formFromEvent(stored), stored);
    expect(body).toEqual({});
    expect(isEmptyUpdate(body)).toBe(true);
    expect(scheduleChanged(formFromEvent(stored), stored)).toBe(false);
  });

  it("sends only the changed text", () => {
    const state = { ...formFromEvent(stored), title: "Opening assembly", location: "" };
    expect(buildUpdateBody(state, stored)).toEqual({ title: "Opening assembly", location: "" });
  });

  it("sends a changed time as a pair, with its kind and zone", () => {
    const state = { ...formFromEvent(stored), startLocal: "2026-12-07T10:00", endLocal: "2026-12-07T11:00" };
    expect(scheduleChanged(state, stored)).toBe(true);
    expect(buildUpdateBody(state, stored)).toEqual({ allDay: false, timeZone: ADDIS, startAt: "2026-12-07T07:00:00.000Z", endAt: "2026-12-07T08:00:00.000Z" });
    // a new end alone still sends both
    const end = { ...formFromEvent(stored), endLocal: "2026-12-07T10:30" };
    expect(buildUpdateBody(end, stored)).toMatchObject({ startAt: "2026-12-07T06:00:00.000Z", endAt: "2026-12-07T07:30:00.000Z" });
  });

  it("treats a zone change as a schedule change", () => {
    const state = { ...formFromEvent(stored), timeZone: "Europe/Berlin" };
    expect(scheduleChanged(state, stored)).toBe(true);
    expect(buildUpdateBody(state, stored).timeZone).toBe("Europe/Berlin");
  });

  it("switches an event between timed and all-day", () => {
    const state = withAllDay(formFromEvent(stored), true);
    expect(buildUpdateBody(state, stored)).toEqual({ allDay: true, timeZone: ADDIS, startDate: "2026-12-07", endDate: "2026-12-07" });
  });

  it("changes, removes and keeps the repeat rule", () => {
    const series: EventView = { ...stored, recurring: true, recurrenceRule: "FREQ=WEEKLY;COUNT=3" };
    const weeklyTo5 = { ...formFromEvent(series), recurrence: { ...formFromEvent(series).recurrence, count: 5 } };
    expect(buildUpdateBody(weeklyTo5, series)).toEqual({ recurrenceRule: "FREQ=WEEKLY;COUNT=5" });
    expect(buildUpdateBody({ ...formFromEvent(series), recurrence: noRecurrence() }, series)).toEqual({ recurrenceRule: "" });
    expect(buildUpdateBody(formFromEvent(series), series)).toEqual({});
    // a rule the builder cannot show is never resent
    const locked: EventView = { ...stored, recurring: true, recurrenceRule: "FREQ=MONTHLY;BYMONTHDAY=1,15" };
    expect(buildUpdateBody(formFromEvent(locked), locked)).toEqual({});
    // making a one-off event repeat
    expect(buildUpdateBody({ ...formFromEvent(stored), recurrence: { ...noRecurrence(), freq: "DAILY", end: "count", count: 3 } }, stored)).toEqual({ recurrenceRule: "FREQ=DAILY;COUNT=3" });
  });

  it("changes the answers switch", () => {
    expect(buildUpdateBody({ ...formFromEvent(stored), rsvpEnabled: true }, stored)).toEqual({ rsvpEnabled: true });
  });

  it("warns when a change clears exceptions or resets answers", () => {
    const series: EventView = { ...stored, recurring: true, recurrenceRule: "FREQ=WEEKLY;COUNT=3", overrides: [{ occurrenceId: "20261214T060000Z", status: "CANCELLED" }] };
    const moved = { ...formFromEvent(series), startLocal: "2026-12-07T10:00", endLocal: "2026-12-07T11:00" };
    expect(updateWarnings(moved, series)).toEqual({ clearsExceptions: true, resetsAnswers: false });
    expect(updateWarnings({ ...formFromEvent(series), title: "New" }, series)).toEqual({ clearsExceptions: false, resetsAnswers: false });
    const asked: EventView = { ...stored, rsvpEnabled: true };
    expect(updateWarnings({ ...formFromEvent(asked), startLocal: "2026-12-07T10:00", endLocal: "2026-12-07T11:00" }, asked)).toEqual({ clearsExceptions: false, resetsAnswers: true });
    expect(updateWarnings({ ...formFromEvent(asked), title: "New" }, asked)).toEqual({ clearsExceptions: false, resetsAnswers: false });
  });
});

describe("editing one occurrence", () => {
  const values: OccurrenceValues = { title: "Choir", location: "Hall", allDay: false, startAt: "2026-12-07T06:00:00Z", endAt: "2026-12-07T07:00:00Z", timeZone: ADDIS };

  it("starts from the occurrence's own values in the event's zone", () => {
    expect(occurrenceForm(values)).toMatchObject({ title: "Choir", location: "Hall", startLocal: "2026-12-07T09:00", endLocal: "2026-12-07T10:00" });
  });

  it("sends only what changed, the time as a pair", () => {
    const state = { ...occurrenceForm(values), location: "Music room", startLocal: "2026-12-08T09:00", endLocal: "2026-12-08T10:00" };
    expect(buildOccurrenceBody(state, values)).toEqual({ location: "Music room", startAt: "2026-12-08T06:00:00.000Z", endAt: "2026-12-08T07:00:00.000Z" });
    expect(buildOccurrenceBody(occurrenceForm(values), values)).toEqual({});
  });

  it("allows a move of at most 7 days", () => {
    const at = (startLocal: string, endLocal: string) => occurrenceProblems({ ...occurrenceForm(values), startLocal, endLocal }, values).map((p) => p.code);
    expect(at("2026-12-14T09:00", "2026-12-14T10:00")).toEqual([]);
    expect(at("2026-12-14T10:00", "2026-12-14T11:00")).toEqual(["shiftTooLarge"]);
    expect(at("2026-11-30T09:00", "2026-11-30T10:00")).toEqual([]);
    expect(at("2026-11-30T08:00", "2026-11-30T09:00")).toEqual(["shiftTooLarge"]);
    expect(at("2026-12-07T10:00", "2026-12-07T09:00")).toEqual(["endBeforeStart"]);
    expect(occurrenceProblems({ ...occurrenceForm(values), title: " " }, values).map((p) => p.code)).toEqual(["titleRequired"]);
  });

  it("keeps all-day occurrences to dates", () => {
    const allDay: OccurrenceValues = { title: "Exams", allDay: true, startAt: "2026-12-06T21:00:00Z", endAt: "2026-12-08T21:00:00Z", startDate: "2026-12-07", endDate: "2026-12-08", timeZone: ADDIS };
    const state = { ...occurrenceForm(allDay), startDate: "2026-12-09", endDate: "2026-12-10" };
    expect(buildOccurrenceBody(state, allDay)).toEqual({ startDate: "2026-12-09", endDate: "2026-12-10" });
    expect(occurrenceProblems({ ...state, startDate: "2026-12-20", endDate: "2026-12-21" }, allDay).map((p) => p.code)).toEqual(["shiftTooLarge"]);
  });
});
