import { stepMinutes } from '@/lib/feed/schedule';

import { addDays, daysBetween, parseIso, type IsoDate } from './civil';
import { occurrenceInstant, occurrenceText, occurrenceTimes } from './occurrence';
import {
  buildRule,
  defaultRepeat,
  followStart,
  NO_REPEAT,
  parseRule,
  repeatProblems,
  type RepeatProblem,
  type RepeatState,
} from './recurrence';
import type {
  CalendarCapabilities,
  CalendarEventRequest,
  CalendarEventUpdateRequest,
  CalendarEventView,
  CalendarOccurrenceUpdateRequest,
  EventType,
} from './types';
import { instantOf, usableZone, zonedParts } from './zone';

/**
 * The event form as plain data: its state, the validation (catalog keys, never English sentences) and the request bodies. Times
 * are wall-clock dates and minutes IN THE EVENT'S ZONE; only `instantOf` turns them into the instants the API stores. The phone's
 * zone never enters. Everything here is memory-only: a form holds titles that may name a child.
 */

export type FormKind = 'SCHOOL' | 'CAMPUS' | 'GRADE_LEVEL' | 'CLASS' | 'PARENT_MEETING' | 'PERSONAL';
export type FormMode = 'create' | 'edit' | 'occurrence';

export const TITLE_MAX = 200;
export const DESCRIPTION_MAX = 5000;
export const LOCATION_MAX = 200;
/** The API refuses an event (or an all-day event) of a year or more, and years outside 1970..2200. */
export const MAX_SPAN_DAYS = 366;
export const MIN_YEAR = 1970;
export const MAX_YEAR = 2200;
/** One occurrence may move at most this many days from its place in the series. */
export const MAX_OCCURRENCE_SHIFT_DAYS = 7;

export interface EventForm {
  mode: FormMode;
  kind: FormKind;
  schoolId: string | null;
  eventType: EventType;
  classId: string | null;
  studentId: string | null;
  title: string;
  description: string;
  location: string;
  allDay: boolean;
  startDate: IsoDate;
  startMinutes: number;
  endDate: IsoDate;
  endMinutes: number;
  timeZone: string;
  repeat: RepeatState;
  rsvpEnabled: boolean;
}

// --- what the caller may create ---------------------------------------------------------------------------------------

export interface KindOption {
  kind: FormKind;
  /** The event types that fit the kind (the first is the default). */
  types: readonly EventType[];
}

const SCHOOL_TYPES: readonly EventType[] = ['SCHOOL_EVENT', 'EXAM', 'OTHER'];
const CLASS_TYPES: readonly EventType[] = ['CLASS_EVENT', 'EXAM', 'OTHER'];

/**
 * What the form offers for a school, from `GET /schools/{id}/events/capabilities` (the API still decides): school events for
 * managers, class events for the classes listed (or any class for a manager), parent meetings for teachers. A personal event
 * needs no school, so it is always there.
 */
export function kindOptions(caps: Pick<CalendarCapabilities, 'canCreateSchoolEvents' | 'canCreateAnyClassEvent' | 'canCreateParentMeetings' | 'classes'> | null | undefined): KindOption[] {
  const options: KindOption[] = [];
  if (caps?.canCreateSchoolEvents) options.push({ kind: 'SCHOOL', types: SCHOOL_TYPES });
  if (caps && (caps.canCreateAnyClassEvent || (caps.classes?.length ?? 0) > 0)) options.push({ kind: 'CLASS', types: CLASS_TYPES });
  if (caps?.canCreateParentMeetings) options.push({ kind: 'PARENT_MEETING', types: ['PARENT_MEETING'] });
  options.push({ kind: 'PERSONAL', types: ['OTHER'] });
  return options;
}

/** The types a kind offers (an existing event's own kind included: campus and grade events are edited, not created, here). */
export function typesFor(kind: FormKind): readonly EventType[] {
  switch (kind) {
    case 'SCHOOL':
    case 'CAMPUS':
    case 'GRADE_LEVEL':
      return SCHOOL_TYPES;
    case 'CLASS':
      return CLASS_TYPES;
    case 'PARENT_MEETING':
      return ['PARENT_MEETING'];
    default:
      return ['OTHER'];
  }
}

export function canAskAnswers(form: Pick<EventForm, 'kind' | 'repeat'>): boolean {
  return form.kind !== 'PERSONAL' && form.kind !== 'PARENT_MEETING' && form.repeat.freq === 'NONE';
}

// --- zones --------------------------------------------------------------------------------------------------------

// A short list of widely used zones after the ones that matter to this person (the event's, the school's, the
// account's, their countries' defaults from /me, the phone's). No country is favoured here (D42).
const COMMON_ZONES = [
  'Africa/Nairobi',
  'Africa/Cairo',
  'Africa/Lagos',
  'Africa/Johannesburg',
  'Europe/London',
  'Europe/Berlin',
  'Asia/Dubai',
  'Asia/Kolkata',
  'Asia/Shanghai',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'UTC',
];

/** The zones the form offers, the event's own first, then the school's, the account's, their countries', the phone's, then common ones. */
export function zoneChoices(preferred: readonly (string | null | undefined)[]): string[] {
  const zones: string[] = [];
  for (const zone of [...preferred, ...COMMON_ZONES]) {
    if (zone && !zones.includes(zone) && usableZone(zone) === zone) zones.push(zone);
  }
  return zones;
}

// --- creating the state -------------------------------------------------------------------------------------------

export interface NewFormOptions {
  kind: FormKind;
  schoolId: string | null;
  /** The IANA zone to start in: the school's from the API, or the account's for a personal event. Never guessed from the phone. */
  timeZone: string;
  nowMs: number;
  /** Start on this day (the day the person was looking at). */
  date?: IsoDate | undefined;
  classId?: string | null | undefined;
  studentId?: string | null | undefined;
}

/** A first choice for the time: the next half hour at least an hour away, an hour long, in the event's zone. */
export function defaultTimes(nowMs: number, timeZone: string, date?: IsoDate): { startDate: IsoDate; startMinutes: number; endDate: IsoDate; endMinutes: number } {
  const zone = usableZone(timeZone);
  let startDate: IsoDate;
  let startMinutes: number;
  if (date) {
    startDate = date;
    startMinutes = 9 * 60;
  } else {
    const local = zonedParts(nowMs + 3_600_000, zone);
    const rounded = Math.ceil(local.minutes / 30) * 30;
    startDate = rounded >= 1440 ? addDays(local.date, 1) : local.date;
    startMinutes = rounded >= 1440 ? 0 : rounded;
  }
  const end = zonedParts(instantOf(startDate, startMinutes, zone) + 3_600_000, zone);
  return { startDate, startMinutes, endDate: end.date, endMinutes: end.minutes };
}

export function newForm({ kind, schoolId, timeZone, nowMs, date, classId = null, studentId = null }: NewFormOptions): EventForm {
  const times = defaultTimes(nowMs, timeZone, date);
  return {
    mode: 'create',
    kind,
    schoolId: kind === 'PERSONAL' ? null : schoolId,
    eventType: typesFor(kind)[0]!,
    classId,
    studentId,
    title: '',
    description: '',
    location: '',
    allDay: false,
    ...times,
    timeZone: usableZone(timeZone) === timeZone ? timeZone : 'UTC',
    repeat: defaultRepeat(times.startDate),
    rsvpEnabled: false,
  };
}

function kindOfView(view: CalendarEventView): FormKind {
  if (view.eventType === 'PARENT_MEETING' || view.scope === 'INVITEES') return 'PARENT_MEETING';
  return view.scope === 'SCHOOL' || view.scope === 'CAMPUS' || view.scope === 'GRADE_LEVEL' || view.scope === 'CLASS' || view.scope === 'PERSONAL' ? view.scope : 'SCHOOL';
}

/** The form of an existing event, for editing it (the kind, school, class and child are shown but cannot change). */
export function formFromEvent(view: CalendarEventView): EventForm {
  const zone = view.timeZone;
  let startDate: IsoDate;
  let startMinutes = 0;
  let endDate: IsoDate;
  let endMinutes = 0;
  if (view.allDay && view.startDate && view.endDate) {
    startDate = view.startDate;
    endDate = view.endDate;
  } else {
    const start = zonedParts(Date.parse(view.startAt ?? ''), zone);
    const end = zonedParts(Date.parse(view.endAt ?? view.startAt ?? ''), zone);
    startDate = start.date;
    startMinutes = start.minutes;
    endDate = end.date;
    endMinutes = end.minutes;
  }
  return {
    mode: 'edit',
    kind: kindOfView(view),
    schoolId: view.schoolId ?? null,
    eventType: view.eventType,
    classId: view.classId ?? null,
    studentId: view.regardingStudentId ?? null,
    title: view.title,
    description: view.description ?? '',
    location: view.location ?? '',
    allDay: !!view.allDay,
    startDate,
    startMinutes,
    endDate,
    endMinutes,
    timeZone: zone,
    repeat: view.recurring ? (parseRule(view.recurrenceRule, startDate, zone) ?? NO_REPEAT) : defaultRepeat(startDate),
    rsvpEnabled: !!view.rsvpEnabled,
  };
}

/** The form of ONE occurrence of a repeating event: its own times and text (only these can differ from the series). */
export function formFromOccurrence(view: CalendarEventView, occurrenceId: string): EventForm {
  const base = formFromEvent(view);
  const times = occurrenceTimes(view, occurrenceId);
  const text = occurrenceText(view, times.override);
  const form: EventForm = { ...base, mode: 'occurrence', title: text.title, description: text.description ?? '', location: text.location ?? '', repeat: NO_REPEAT, rsvpEnabled: false };
  if (times.allDay && times.startDate && times.endDate) {
    form.startDate = times.startDate;
    form.endDate = times.endDate;
  } else if (times.startMs !== null && times.endMs !== null) {
    const start = zonedParts(times.startMs, view.timeZone);
    const end = zonedParts(times.endMs, view.timeZone);
    form.startDate = start.date;
    form.startMinutes = start.minutes;
    form.endDate = end.date;
    form.endMinutes = end.minutes;
  }
  return form;
}

// --- editing the state ----------------------------------------------------------------------------------------------

function startMs(form: EventForm): number {
  return instantOf(form.startDate, form.allDay ? 0 : form.startMinutes, form.timeZone);
}

function endMs(form: EventForm): number {
  return instantOf(form.endDate, form.allDay ? 0 : form.endMinutes, form.timeZone);
}

/** Changes the start and moves the end with it, keeping the length (all-day: the number of days). */
export function withStart(form: EventForm, startDate: IsoDate, startMinutes: number): EventForm {
  const next = { ...form, startDate, startMinutes };
  if (form.allDay) {
    const days = Math.max(0, daysBetween(form.startDate, form.endDate));
    next.endDate = addDays(startDate, Number.isNaN(days) ? 0 : days);
  } else {
    const length = endMs(form) - startMs(form);
    const end = zonedParts(instantOf(startDate, startMinutes, form.timeZone) + (Number.isFinite(length) && length >= 0 ? length : 3_600_000), form.timeZone);
    next.endDate = end.date;
    next.endMinutes = end.minutes;
  }
  next.repeat = followStart(form.repeat, form.startDate, startDate);
  return next;
}

/** Changes the end (the validation says if it lies before the start). */
export function withEnd(form: EventForm, endDate: IsoDate, endMinutes: number): EventForm {
  return { ...form, endDate, endMinutes };
}

export function withAllDay(form: EventForm, allDay: boolean): EventForm {
  if (allDay === form.allDay) return form;
  if (allDay) return { ...form, allDay, endDate: form.endDate < form.startDate ? form.startDate : form.endDate };
  // Back to timed: the same day, a one hour slot from the start time kept in the form.
  const end = zonedParts(instantOf(form.startDate, form.startMinutes, form.timeZone) + 3_600_000, form.timeZone);
  return { ...form, allDay, endDate: form.endDate > form.startDate ? form.endDate : end.date, endMinutes: form.endDate > form.startDate ? form.endMinutes : end.minutes };
}

/** Changes the zone and keeps the clock times the person typed (09:00 stays 09:00, now in the new zone). */
export function withZone(form: EventForm, timeZone: string): EventForm {
  return { ...form, timeZone };
}

/** The zones a new event may start in: the school's from the API (never the phone's) and the account's own for a personal one. */
export interface KindZones {
  schoolZone: string | null;
  userZone: string;
}

/** Another kind of event: the audience resets, the zone follows the kind (the school's, or the account's for a personal event). */
export function switchKind(form: EventForm, kind: FormKind, { schoolZone, userZone }: KindZones): EventForm {
  const meeting = kind === 'PARENT_MEETING';
  return {
    ...form,
    kind,
    eventType: typesFor(kind)[0]!,
    classId: null,
    studentId: null,
    timeZone: kind === 'PERSONAL' ? userZone : (schoolZone ?? form.timeZone),
    allDay: meeting ? false : form.allDay,
    repeat: meeting ? NO_REPEAT : form.repeat,
    rsvpEnabled: kind === 'PERSONAL' || meeting ? false : form.rsvpEnabled,
  };
}

/** One step of the time field: `delta` times `step` minutes, rolling over midnight into the neighbouring day. */
export function stepTime(date: IsoDate, minutes: number, delta: 1 | -1, step = 15): { date: IsoDate; minutes: number } {
  return stepMinutes({ date, minutes }, delta, step);
}

// --- validation ---------------------------------------------------------------------------------------------------

export type FormField = 'title' | 'description' | 'location' | 'audience' | 'class' | 'student' | 'start' | 'end' | 'timeZone' | 'repeat' | 'rsvp';

export interface FormIssue {
  field: FormField;
  /** Catalog key: `mobile.calendar.form.problem.*`. */
  key: string;
  values?: Record<string, number>;
}

const PROBLEM = 'mobile.calendar.form.problem';

function yearOk(date: IsoDate): boolean {
  const ymd = parseIso(date);
  return !!ymd && ymd.year >= MIN_YEAR && ymd.year <= MAX_YEAR;
}

/**
 * Everything wrong with the form that the phone can tell without the API (which still has the last word). `nowMs` is needed for
 * parent meetings, which must lie in the future.
 */
export function validateForm(form: EventForm, nowMs: number, original?: EventForm | null): FormIssue[] {
  const issues: FormIssue[] = [];
  const title = form.title.trim();
  if (!title) issues.push({ field: 'title', key: `${PROBLEM}.titleRequired` });
  else if (title.length > TITLE_MAX) issues.push({ field: 'title', key: `${PROBLEM}.titleTooLong`, values: { max: TITLE_MAX } });
  if (form.description.trim().length > DESCRIPTION_MAX) issues.push({ field: 'description', key: `${PROBLEM}.descriptionTooLong`, values: { max: DESCRIPTION_MAX } });
  if (form.location.trim().length > LOCATION_MAX) issues.push({ field: 'location', key: `${PROBLEM}.locationTooLong`, values: { max: LOCATION_MAX } });

  if (form.mode === 'create') {
    if (form.kind === 'CLASS' && !form.classId) issues.push({ field: 'class', key: `${PROBLEM}.classRequired` });
    if (form.kind === 'PARENT_MEETING') {
      if (!form.classId) issues.push({ field: 'class', key: `${PROBLEM}.classRequired` });
      if (!form.studentId) issues.push({ field: 'student', key: `${PROBLEM}.studentRequired` });
    }
    if (form.kind !== 'PERSONAL' && !form.schoolId) issues.push({ field: 'audience', key: `${PROBLEM}.schoolRequired` });
    if (!typesFor(form.kind).includes(form.eventType)) issues.push({ field: 'audience', key: `${PROBLEM}.typeInvalid` });
  }

  if (form.mode !== 'occurrence' && usableZone(form.timeZone) !== form.timeZone) issues.push({ field: 'timeZone', key: `${PROBLEM}.timeZoneUnknown` });

  if (!yearOk(form.startDate)) issues.push({ field: 'start', key: `${PROBLEM}.dateInvalid` });
  else if (!yearOk(form.endDate)) issues.push({ field: 'end', key: `${PROBLEM}.dateInvalid` });
  else if (form.allDay) {
    if (form.endDate < form.startDate) issues.push({ field: 'end', key: `${PROBLEM}.endBeforeStart` });
    else if (daysBetween(form.startDate, form.endDate) >= MAX_SPAN_DAYS) issues.push({ field: 'end', key: `${PROBLEM}.tooLong` });
  } else {
    const start = startMs(form);
    const end = endMs(form);
    if (end < start) issues.push({ field: 'end', key: `${PROBLEM}.endBeforeStart` });
    else if ((end - start) / 86_400_000 >= MAX_SPAN_DAYS) issues.push({ field: 'end', key: `${PROBLEM}.tooLong` });
  }

  if (form.mode !== 'occurrence') {
    for (const problem of repeatProblems(form.repeat, form.startDate)) issues.push({ field: 'repeat', key: `${PROBLEM}.repeat.${problem}` });
    if (form.rsvpEnabled && !canAskAnswers(form)) issues.push({ field: 'rsvp', key: `${PROBLEM}.rsvpNotWithRepeat` });
  }

  if (form.kind === 'PARENT_MEETING') {
    if (form.allDay) issues.push({ field: 'start', key: `${PROBLEM}.meetingTimed` });
    const scheduleEdited = form.mode === 'create' || !original || scheduleDiffers(original, form);
    if (scheduleEdited && !form.allDay && startMs(form) <= nowMs) issues.push({ field: 'start', key: `${PROBLEM}.meetingPast` });
  }

  if (form.mode === 'occurrence' && original) {
    const shift = Math.abs(startMs(form) - startMs(original)) / 86_400_000;
    if (shift > MAX_OCCURRENCE_SHIFT_DAYS) issues.push({ field: 'start', key: `${PROBLEM}.occurrenceShift`, values: { days: MAX_OCCURRENCE_SHIFT_DAYS } });
  }
  return issues;
}

export function issueFor(issues: readonly FormIssue[], field: FormField): FormIssue | undefined {
  return issues.find((issue) => issue.field === field);
}

export type RepeatProblemKey = `${typeof PROBLEM}.repeat.${RepeatProblem}`;

// --- requests -----------------------------------------------------------------------------------------------------

function clean(text: string): string {
  return text.trim();
}

function timesOf(form: EventForm): Pick<CalendarEventRequest, 'allDay' | 'startAt' | 'endAt' | 'startDate' | 'endDate'> {
  return form.allDay
    ? { allDay: true, startDate: form.startDate, endDate: form.endDate }
    : { allDay: false, startAt: new Date(startMs(form)).toISOString(), endAt: new Date(endMs(form)).toISOString() };
}

/** The body of `POST /schools/{id}/events` or `POST /me/events`. The time zone is always sent: the event's, not the phone's. */
export function createRequest(form: EventForm): CalendarEventRequest {
  const request: CalendarEventRequest = {
    title: clean(form.title),
    timeZone: form.timeZone,
    ...timesOf(form),
  };
  const description = clean(form.description);
  const location = clean(form.location);
  if (description) request.description = description;
  if (location) request.location = location;
  switch (form.kind) {
    case 'PERSONAL':
      request.scope = 'PERSONAL';
      request.eventType = 'OTHER';
      break;
    case 'PARENT_MEETING':
      request.eventType = 'PARENT_MEETING';
      if (form.studentId) request.regardingStudentId = form.studentId;
      break;
    case 'CLASS':
      request.scope = 'CLASS';
      request.eventType = form.eventType;
      if (form.classId) request.classId = form.classId;
      break;
    default:
      request.scope = 'SCHOOL';
      request.eventType = form.eventType;
  }
  const rule = form.kind === 'PARENT_MEETING' ? null : buildRule(form.repeat);
  if (rule) request.recurrenceRule = rule;
  if (form.rsvpEnabled && canAskAnswers(form)) request.rsvpEnabled = true;
  return request;
}

/** Whether the time, zone or repeat differs between two forms (what clears exceptions and answers on the server). */
export function scheduleDiffers(before: EventForm, after: EventForm): boolean {
  if (before.allDay !== after.allDay || before.timeZone !== after.timeZone) return true;
  if (buildRule(before.repeat) !== buildRule(after.repeat)) return true;
  return before.allDay
    ? before.startDate !== after.startDate || before.endDate !== after.endDate
    : startMs(before) !== startMs(after) || endMs(before) !== endMs(after);
}

/** Only what changed: sending nothing for the time keeps the series' exceptions and everybody's answers. */
export function updateRequest(before: EventForm, after: EventForm): CalendarEventUpdateRequest {
  const request: CalendarEventUpdateRequest = {};
  if (clean(after.title) !== clean(before.title)) request.title = clean(after.title);
  if (clean(after.description) !== clean(before.description)) request.description = clean(after.description);
  if (clean(after.location) !== clean(before.location)) request.location = clean(after.location);
  if (scheduleDiffers(before, after)) {
    if (after.timeZone !== before.timeZone) request.timeZone = after.timeZone;
    const timesChanged = before.allDay !== after.allDay || (after.allDay ? before.startDate !== after.startDate || before.endDate !== after.endDate : startMs(before) !== startMs(after) || endMs(before) !== endMs(after));
    if (timesChanged || after.timeZone !== before.timeZone) Object.assign(request, timesOf(after));
    const rule = buildRule(after.repeat);
    if (rule !== buildRule(before.repeat)) request.recurrenceRule = rule ?? '';
  }
  if (after.rsvpEnabled !== before.rsvpEnabled && after.kind !== 'PARENT_MEETING') request.rsvpEnabled = canAskAnswers(after) ? after.rsvpEnabled : false;
  return request;
}

/** The body of `PATCH /events/{id}/occurrences/{occurrenceId}`: what differs from the occurrence as it is now. */
export function occurrenceRequest(before: EventForm, after: EventForm): CalendarOccurrenceUpdateRequest {
  const request: CalendarOccurrenceUpdateRequest = {};
  if (clean(after.title) !== clean(before.title)) request.title = clean(after.title);
  if (clean(after.description) !== clean(before.description)) request.description = clean(after.description);
  if (clean(after.location) !== clean(before.location)) request.location = clean(after.location);
  if (after.allDay) {
    if (after.startDate !== before.startDate || after.endDate !== before.endDate) {
      request.startDate = after.startDate;
      request.endDate = after.endDate;
    }
  } else if (startMs(before) !== startMs(after) || endMs(before) !== endMs(after)) {
    request.startAt = new Date(startMs(after)).toISOString();
    request.endAt = new Date(endMs(after)).toISOString();
  }
  return request;
}

export function isEmptyRequest(request: object): boolean {
  return Object.keys(request).length === 0;
}

// --- warnings before a change is saved -------------------------------------------------------------------------------

export interface ChangeWarnings {
  /** The series' exceptions (cancelled or moved occurrences) are cleared. */
  clearsExceptions: boolean;
  /** People who answered are asked again. */
  resetsAnswers: boolean;
  /** The people invited or the audience are told of a new time or place. */
  notifies: boolean;
}

/** What saving the edit does besides the change itself (the warning shown before it is saved). */
export function changeWarnings(view: CalendarEventView, before: EventForm, after: EventForm): ChangeWarnings {
  const schedule = scheduleDiffers(before, after);
  const place = clean(before.location) !== clean(after.location);
  return {
    clearsExceptions: schedule && !!view.recurring && (view.overrides?.length ?? 0) > 0,
    resetsAnswers: schedule && !!view.rsvp?.enabled,
    notifies: (schedule || place) && view.scope !== 'PERSONAL',
  };
}

export function hasWarnings(warnings: ChangeWarnings): boolean {
  return warnings.clearsExceptions || warnings.resetsAnswers || warnings.notifies;
}

/** The earliest instant an occurrence may be moved to and the latest (7 days either way of its place in the series). */
export function occurrenceBounds(occurrenceId: string): { earliest: number; latest: number } | null {
  const at = occurrenceInstant(occurrenceId);
  if (at === null) return null;
  return { earliest: at - MAX_OCCURRENCE_SHIFT_DAYS * 86_400_000, latest: at + MAX_OCCURRENCE_SHIFT_DAYS * 86_400_000 };
}
