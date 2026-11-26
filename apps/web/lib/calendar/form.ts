import type { components } from "@school/api-client";
import { instantToLocal, localToInstant } from "@/lib/feed/schedule";
import { diffDays, isIsoDate } from "./dates";
import { defaultTypeForScope, scopeAllowsAllDay, scopeAllowsRecurrence, scopeOffersRsvp, typesForScope, type EventScope, type EventType } from "./access";
import { buildRule, noRecurrence, parseRule, recurrenceProblems, type RecurrenceDraft, type RecurrenceProblem } from "./recurrence";
import type { EventView } from "./rsvp";

type S = components["schemas"];
export type CreateBody = S["CalendarEventRequest"];
export type UpdateBody = S["CalendarEventUpdateRequest"];
export type OccurrenceBody = S["CalendarOccurrenceUpdateRequest"];

export { instantToLocal, localToInstant };

export const TITLE_MAX = 200;
export const DESCRIPTION_MAX = 5000;
export const LOCATION_MAX = 200;
/** The API refuses events (and all-day spans) of a year or more. */
export const MAX_SPAN_DAYS = 366;
/** An occurrence may move at most this many days from its place in the series. */
export const MAX_OCCURRENCE_SHIFT_DAYS = 7;

/**
 * The event form's state and what it sends. Pure: the clock and the time zone are arguments, and the time zone is always
 * explicit (the school's, the person's own, or one the person chose: never silently the browser's). Wall-clock fields are
 * read in `timeZone`. The API re-validates everything; the messages here are keys under `school.calendar.problems`.
 */
export interface EventFormState {
  scope: EventScope;
  /** null for a personal event. */
  schoolId: string | null;
  campusId: string;
  gradeLevelId: string;
  classId: string;
  /** Parent meeting: the student it is about (guardians are resolved by the API). */
  studentId: string;
  eventType: EventType;
  title: string;
  description: string;
  location: string;
  allDay: boolean;
  /** "yyyy-MM-ddTHH:mm" on the wall clock of `timeZone` (timed events). */
  startLocal: string;
  endLocal: string;
  /** "yyyy-mm-dd" (all-day events; the end day is included). */
  startDate: string;
  endDate: string;
  timeZone: string;
  recurrence: RecurrenceDraft;
  /** A stored rule the builder cannot show (several month days ...): kept as it is. */
  lockedRule: string | null;
  rsvpEnabled: boolean;
}

export type FormField =
  | "scope"
  | "campus"
  | "grade"
  | "class"
  | "student"
  | "title"
  | "description"
  | "location"
  | "start"
  | "end"
  | "timeZone"
  | "recurrence"
  | "rsvp";

export type ProblemCode =
  | "titleRequired"
  | "titleTooLong"
  | "descriptionTooLong"
  | "locationTooLong"
  | "campusRequired"
  | "gradeRequired"
  | "classRequired"
  | "studentRequired"
  | "timeZoneInvalid"
  | "startInvalid"
  | "endInvalid"
  | "endBeforeStart"
  | "tooLong"
  | "meetingInPast"
  | "rsvpRecurring"
  | RecurrenceProblem;

export interface FormProblem {
  field: FormField;
  code: ProblemCode;
}

/** A time zone name the API accepts: an IANA region name (offsets like +03:00 are refused by the API). */
export function isValidTimeZone(name: string): boolean {
  if (!/^[A-Za-z][A-Za-z0-9_+-]*(\/[A-Za-z0-9_+-]+)*$/.test(name)) return false;
  try {
    new Intl.DateTimeFormat("en", { timeZone: name });
    return true;
  } catch {
    return false;
  }
}

function isLocal(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value);
}

/** The day part of a local value: "2026-12-07T09:00" gives "2026-12-07". */
export function localDay(local: string): string {
  return local.slice(0, 10);
}

export interface InitialOptions {
  scope: EventScope;
  schoolId: string | null;
  timeZone: string;
  now: Date;
  /** The day clicked in the calendar ("yyyy-mm-dd"), else tomorrow. */
  day?: string | null;
}

/** A blank form: tomorrow (or the chosen day) from 08:00 to 09:00 in `timeZone`; all-day dates the same day. */
export function initialForm({ scope, schoolId, timeZone, now, day }: InitialOptions): EventFormState {
  const tomorrow = instantToLocal(new Date(now.getTime() + 86_400_000), timeZone)?.slice(0, 10) ?? "";
  const date = day && isIsoDate(day) ? day : tomorrow;
  return {
    scope,
    schoolId: scope === "PERSONAL" ? null : schoolId,
    campusId: "",
    gradeLevelId: "",
    classId: "",
    studentId: "",
    eventType: defaultTypeForScope(scope),
    title: "",
    description: "",
    location: "",
    allDay: false,
    startLocal: date ? `${date}T08:00` : "",
    endLocal: date ? `${date}T09:00` : "",
    startDate: date,
    endDate: date,
    timeZone,
    recurrence: noRecurrence(),
    lockedRule: null,
    rsvpEnabled: false,
  };
}

/** Changing the scope resets what depends on it (the type, the ids of the other scopes, switches the scope does not have). */
export function withScope(state: EventFormState, scope: EventScope): EventFormState {
  return {
    ...state,
    scope,
    eventType: typesForScope(scope).includes(state.eventType) ? state.eventType : defaultTypeForScope(scope),
    campusId: scope === "CAMPUS" ? state.campusId : "",
    gradeLevelId: scope === "GRADE_LEVEL" ? state.gradeLevelId : "",
    classId: scope === "CLASS" ? state.classId : "",
    studentId: scope === "PARENT_MEETING" ? state.studentId : "",
    allDay: scopeAllowsAllDay(scope) ? state.allDay : false,
    recurrence: scopeAllowsRecurrence(scope) ? state.recurrence : noRecurrence(),
    lockedRule: scopeAllowsRecurrence(scope) ? state.lockedRule : null,
    rsvpEnabled: scopeOffersRsvp(scope) ? state.rsvpEnabled : false,
  };
}

/** A new start moves the end with it (keeping the event's length) when both times are valid; otherwise only the start changes. */
export function withStart(state: EventFormState, startLocal: string): EventFormState {
  const oldStart = localToInstant(state.startLocal, state.timeZone);
  const oldEnd = localToInstant(state.endLocal, state.timeZone);
  const newStart = localToInstant(startLocal, state.timeZone);
  if (!oldStart || !oldEnd || !newStart || oldEnd.getTime() < oldStart.getTime()) return { ...state, startLocal };
  const end = instantToLocal(new Date(newStart.getTime() + (oldEnd.getTime() - oldStart.getTime())), state.timeZone);
  return { ...state, startLocal, endLocal: end ?? state.endLocal };
}

/** Switching between timed and all-day keeps the day: the other representation is derived from it. */
export function withAllDay(state: EventFormState, allDay: boolean): EventFormState {
  if (allDay === state.allDay) return state;
  if (allDay) {
    const start = localDay(state.startLocal) || state.startDate;
    const end = localDay(state.endLocal) || start;
    return { ...state, allDay, startDate: start, endDate: end < start ? start : end };
  }
  const start = state.startDate || localDay(state.startLocal);
  const end = state.endDate || start;
  return {
    ...state,
    allDay,
    startLocal: start ? `${start}T${state.startLocal.slice(11) || "08:00"}` : state.startLocal,
    endLocal: end ? `${end}T${state.endLocal.slice(11) || "09:00"}` : state.endLocal,
  };
}

/** The day the event starts on, as the recurrence builder needs it (a date on the event's own wall clock). */
export function startDayOf(state: Pick<EventFormState, "allDay" | "startDate" | "startLocal">): string {
  return state.allDay ? state.startDate : localDay(state.startLocal);
}

/** True when the repeat switch is on (a draft that repeats, or a stored rule kept as it is). */
export function repeats(state: Pick<EventFormState, "recurrence" | "lockedRule">): boolean {
  return state.recurrence.freq !== "NONE" || state.lockedRule !== null;
}

// --- validation ---------------------------------------------------------------------------

/** The time the form describes, as instants (timed) or dates (all-day); null parts are invalid. */
export interface Resolved {
  start: Date | null;
  end: Date | null;
}

export function resolveTimes(state: Pick<EventFormState, "allDay" | "startLocal" | "endLocal" | "timeZone">): Resolved {
  if (state.allDay) return { start: null, end: null };
  return { start: localToInstant(state.startLocal, state.timeZone), end: localToInstant(state.endLocal, state.timeZone) };
}

export function formProblems(state: EventFormState, now: Date): FormProblem[] {
  const out: FormProblem[] = [];
  const add = (field: FormField, code: ProblemCode) => out.push({ field, code });
  const title = state.title.trim();
  if (!title) add("title", "titleRequired");
  else if ([...title].length > TITLE_MAX) add("title", "titleTooLong");
  if ([...state.description.trim()].length > DESCRIPTION_MAX) add("description", "descriptionTooLong");
  if ([...state.location.trim()].length > LOCATION_MAX) add("location", "locationTooLong");

  if (state.scope === "CAMPUS" && !state.campusId) add("campus", "campusRequired");
  if (state.scope === "GRADE_LEVEL" && !state.gradeLevelId) add("grade", "gradeRequired");
  if (state.scope === "CLASS" && !state.classId) add("class", "classRequired");
  if (state.scope === "PARENT_MEETING" && !state.studentId) add("student", "studentRequired");

  if (!isValidTimeZone(state.timeZone)) add("timeZone", "timeZoneInvalid");

  if (state.allDay) {
    const okStart = isIsoDate(state.startDate);
    const okEnd = isIsoDate(state.endDate);
    if (!okStart) add("start", "startInvalid");
    if (!okEnd) add("end", "endInvalid");
    if (okStart && okEnd) {
      if (state.endDate < state.startDate) add("end", "endBeforeStart");
      else if (diffDays(state.startDate, state.endDate) >= MAX_SPAN_DAYS) add("end", "tooLong");
    }
  } else if (isValidTimeZone(state.timeZone)) {
    const { start, end } = resolveTimes(state);
    if (!isLocal(state.startLocal) || !start) add("start", "startInvalid");
    if (!isLocal(state.endLocal) || !end) add("end", "endInvalid");
    if (start && end) {
      if (end.getTime() < start.getTime()) add("end", "endBeforeStart");
      else if ((end.getTime() - start.getTime()) / 86_400_000 >= MAX_SPAN_DAYS) add("end", "tooLong");
    }
    if (state.scope === "PARENT_MEETING" && start && start.getTime() <= now.getTime()) add("start", "meetingInPast");
  }

  const startDay = startDayOf(state);
  if (state.recurrence.freq !== "NONE" && isIsoDate(startDay)) {
    for (const code of recurrenceProblems(state.recurrence, startDay)) add("recurrence", code);
  }
  if (repeats(state) && state.rsvpEnabled) add("rsvp", "rsvpRecurring");
  return out;
}

export function problemFor(problems: readonly FormProblem[], field: FormField): ProblemCode | null {
  return problems.find((p) => p.field === field)?.code ?? null;
}

// --- what is sent -------------------------------------------------------------------------

/** The `POST /schools/{id}/events` or `/me/events` body. Call only when `formProblems` is empty. */
export function buildCreateBody(state: EventFormState): CreateBody {
  const body: CreateBody = {
    title: state.title.trim(),
    timeZone: state.timeZone,
  };
  const description = state.description.trim();
  const location = state.location.trim();
  if (description) body.description = description;
  if (location) body.location = location;

  if (state.scope === "PARENT_MEETING") {
    // No scope: the API invites the student's guardians itself.
    body.eventType = "PARENT_MEETING";
    body.regardingStudentId = state.studentId;
  } else if (state.scope !== "PERSONAL") {
    body.scope = state.scope;
    body.eventType = state.eventType;
    if (state.scope === "CAMPUS") body.campusId = state.campusId;
    if (state.scope === "GRADE_LEVEL") body.gradeLevelId = state.gradeLevelId;
    if (state.scope === "CLASS") body.classId = state.classId;
  }

  if (state.allDay) {
    body.allDay = true;
    body.startDate = state.startDate;
    body.endDate = state.endDate;
  } else {
    const { start, end } = resolveTimes(state);
    body.startAt = start?.toISOString();
    body.endAt = end?.toISOString();
  }

  if (scopeAllowsRecurrence(state.scope)) {
    const rule = state.lockedRule ?? buildRule(state.recurrence, startDayOf(state));
    if (rule) body.recurrenceRule = rule;
  }
  if (scopeOffersRsvp(state.scope) && state.rsvpEnabled) body.rsvpEnabled = true;
  return body;
}

/** The form of an existing event, read in the event's own time zone. */
export function formFromEvent(event: EventView): EventFormState {
  const zone = event.timeZone;
  const allDay = !!event.allDay;
  const startLocal = !allDay && event.startAt ? (instantToLocal(event.startAt, zone) ?? "") : "";
  const endLocal = !allDay && event.endAt ? (instantToLocal(event.endAt, zone) ?? "") : "";
  const parsed = parseRule(event.recurrenceRule, zone);
  const scope: EventScope = event.eventType === "PARENT_MEETING" ? "PARENT_MEETING" : event.scope === "INVITEES" ? "PARENT_MEETING" : (event.scope as EventScope);
  return {
    scope,
    schoolId: event.schoolId ?? null,
    campusId: event.campusId ?? "",
    gradeLevelId: event.gradeLevelId ?? "",
    classId: event.classId ?? "",
    studentId: event.regardingStudentId ?? "",
    eventType: event.eventType,
    title: event.title,
    description: event.description ?? "",
    location: event.location ?? "",
    allDay,
    startLocal,
    endLocal,
    startDate: allDay ? (event.startDate ?? "") : startLocal.slice(0, 10),
    endDate: allDay ? (event.endDate ?? event.startDate ?? "") : endLocal.slice(0, 10),
    timeZone: zone,
    recurrence: parsed ?? noRecurrence(),
    lockedRule: event.recurrenceRule && !parsed ? event.recurrenceRule : null,
    rsvpEnabled: !!event.rsvpEnabled && scope !== "PARENT_MEETING",
  };
}

/** True when the form's schedule (kind, time, zone) differs from the stored event's. */
export function scheduleChanged(state: EventFormState, original: EventView): boolean {
  if (state.allDay !== !!original.allDay || state.timeZone !== original.timeZone) return true;
  if (state.allDay) return state.startDate !== original.startDate || state.endDate !== (original.endDate ?? original.startDate);
  const { start, end } = resolveTimes(state);
  return start?.getTime() !== new Date(original.startAt ?? 0).getTime() || end?.getTime() !== new Date(original.endAt ?? 0).getTime();
}

/** The rule the form would send, or null when the event does not repeat. */
export function ruleOf(state: EventFormState): string | null {
  return state.lockedRule ?? buildRule(state.recurrence, startDayOf(state));
}

/** True when the repeat rule differs from the stored one (compared as the API stores it: the canonical text). */
export function ruleChanged(state: EventFormState, original: EventView): boolean {
  if (state.lockedRule !== null) return false;
  return (ruleOf(state) ?? "") !== (original.recurrenceRule ?? "");
}

/**
 * The `PATCH /events/{id}` body: only what changed. A new time is sent whole (start and end together, with the kind and the
 * zone), an emptied description, place or rule is sent as "" (the API removes it). Scope, type and student never change.
 */
export function buildUpdateBody(state: EventFormState, original: EventView): UpdateBody {
  const body: UpdateBody = {};
  const title = state.title.trim();
  if (title !== original.title) body.title = title;
  const description = state.description.trim();
  if (description !== (original.description ?? "")) body.description = description;
  const location = state.location.trim();
  if (location !== (original.location ?? "")) body.location = location;

  if (scheduleChanged(state, original)) {
    body.timeZone = state.timeZone;
    body.allDay = state.allDay;
    if (state.allDay) {
      body.startDate = state.startDate;
      body.endDate = state.endDate;
    } else {
      const { start, end } = resolveTimes(state);
      body.startAt = start?.toISOString();
      body.endAt = end?.toISOString();
    }
  }
  if (ruleChanged(state, original)) body.recurrenceRule = ruleOf(state) ?? "";
  if (scopeOffersRsvp(state.scope) && state.rsvpEnabled !== !!original.rsvpEnabled) body.rsvpEnabled = state.rsvpEnabled;
  return body;
}

/** Changing the schedule of a repeating event clears the series' exceptions; any schedule change of an event with answers resets them. */
export function updateWarnings(state: EventFormState, original: EventView): { clearsExceptions: boolean; resetsAnswers: boolean } {
  const schedule = scheduleChanged(state, original);
  const rule = ruleChanged(state, original);
  return {
    clearsExceptions: !!original.recurring && (schedule || rule) && (original.overrides?.length ?? 0) > 0,
    resetsAnswers: !!original.rsvpEnabled && schedule,
  };
}

/** True when the update would change nothing (the form can stay open without a call). */
export function isEmptyUpdate(body: UpdateBody): boolean {
  return Object.keys(body).length === 0;
}

// --- one occurrence -----------------------------------------------------------------------

/** What "only this event" edits: title, place, text and the time (moved by at most 7 days, in the same kind). */
export interface OccurrenceFormState {
  title: string;
  description: string;
  location: string;
  allDay: boolean;
  startLocal: string;
  endLocal: string;
  startDate: string;
  endDate: string;
  timeZone: string;
}

/** The current values of one occurrence (an item of the union: it already carries the series' values and its exception). */
export interface OccurrenceValues {
  title: string;
  description?: string;
  location?: string;
  allDay: boolean;
  startAt: string;
  endAt: string;
  startDate?: string;
  endDate?: string;
  timeZone: string;
}

export function occurrenceForm(values: OccurrenceValues): OccurrenceFormState {
  const startLocal = values.allDay ? "" : (instantToLocal(values.startAt, values.timeZone) ?? "");
  const endLocal = values.allDay ? "" : (instantToLocal(values.endAt, values.timeZone) ?? "");
  return {
    title: values.title,
    description: values.description ?? "",
    location: values.location ?? "",
    allDay: values.allDay,
    startLocal,
    endLocal,
    startDate: values.allDay ? (values.startDate ?? "") : startLocal.slice(0, 10),
    endDate: values.allDay ? (values.endDate ?? values.startDate ?? "") : endLocal.slice(0, 10),
    timeZone: values.timeZone,
  };
}

export type OccurrenceProblem = "titleRequired" | "titleTooLong" | "locationTooLong" | "descriptionTooLong" | "startInvalid" | "endInvalid" | "endBeforeStart" | "shiftTooLarge";

export function occurrenceProblems(state: OccurrenceFormState, original: OccurrenceValues): { field: FormField; code: OccurrenceProblem }[] {
  const out: { field: FormField; code: OccurrenceProblem }[] = [];
  const add = (field: FormField, code: OccurrenceProblem) => out.push({ field, code });
  const title = state.title.trim();
  if (!title) add("title", "titleRequired");
  else if ([...title].length > TITLE_MAX) add("title", "titleTooLong");
  if ([...state.description.trim()].length > DESCRIPTION_MAX) add("description", "descriptionTooLong");
  if ([...state.location.trim()].length > LOCATION_MAX) add("location", "locationTooLong");
  if (state.allDay) {
    if (!isIsoDate(state.startDate)) add("start", "startInvalid");
    if (!isIsoDate(state.endDate)) add("end", "endInvalid");
    if (isIsoDate(state.startDate) && isIsoDate(state.endDate)) {
      if (state.endDate < state.startDate) add("end", "endBeforeStart");
      const ref = original.startDate ?? "";
      if (isIsoDate(ref) && Math.abs(diffDays(ref, state.startDate)) > MAX_OCCURRENCE_SHIFT_DAYS) add("start", "shiftTooLarge");
    }
  } else {
    const { start, end } = resolveTimes(state);
    if (!start) add("start", "startInvalid");
    if (!end) add("end", "endInvalid");
    if (start && end) {
      if (end.getTime() < start.getTime()) add("end", "endBeforeStart");
      if (Math.abs(start.getTime() - new Date(original.startAt).getTime()) > MAX_OCCURRENCE_SHIFT_DAYS * 86_400_000) add("start", "shiftTooLarge");
    }
  }
  return out;
}

/** The `PATCH .../occurrences/{id}` body: changed fields only, the time as a pair. The occurrence keeps its zone and kind. */
export function buildOccurrenceBody(state: OccurrenceFormState, original: OccurrenceValues): OccurrenceBody {
  const body: OccurrenceBody = {};
  const title = state.title.trim();
  if (title !== original.title) body.title = title;
  const description = state.description.trim();
  if (description !== (original.description ?? "")) body.description = description;
  const location = state.location.trim();
  if (location !== (original.location ?? "")) body.location = location;
  if (state.allDay) {
    if (state.startDate !== original.startDate || state.endDate !== (original.endDate ?? original.startDate)) {
      body.startDate = state.startDate;
      body.endDate = state.endDate;
    }
  } else {
    const { start, end } = resolveTimes(state);
    if (start && end && (start.getTime() !== new Date(original.startAt).getTime() || end.getTime() !== new Date(original.endAt).getTime())) {
      body.startAt = start.toISOString();
      body.endAt = end.toISOString();
    }
  }
  return body;
}
