import { describe, expect, it } from 'vitest';

import {
  canAskAnswers,
  changeWarnings,
  createRequest,
  defaultTimes,
  formFromEvent,
  formFromOccurrence,
  hasWarnings,
  isEmptyRequest,
  issueFor,
  kindOptions,
  newForm,
  occurrenceRequest,
  scheduleDiffers,
  stepTime,
  switchKind,
  updateRequest,
  validateForm,
  withAllDay,
  withEnd,
  withStart,
  withZone,
  zoneChoices,
  type EventForm,
} from './form';
import { buildRule } from './recurrence';
import type { CalendarEventView } from './types';

const ADDIS = 'Africa/Addis_Ababa';
const NEW_YORK = 'America/New_York';
// Thursday 26 November 2026, 10:00 in Addis Ababa.
const NOW = Date.UTC(2026, 10, 26, 7, 0);

const CLASS_ID = '5b3b9f5e-0f3c-4a58-9c63-3f1a4e2d7a10';
const STUDENT_ID = '6c4c0a6f-1a4d-4b69-8d74-4a2b5f3e8b21';
const SCHOOL_ID = '7d5d1b70-2b5e-4c7a-9e85-5b3c6a4f9c32';

function form(over: Partial<EventForm> = {}): EventForm {
  return { ...newForm({ kind: 'SCHOOL', schoolId: SCHOOL_ID, timeZone: ADDIS, nowMs: NOW }), title: 'Sports day', ...over };
}

describe('what the form offers', () => {
  it('follows the capabilities of the API', () => {
    expect(kindOptions({ canCreateSchoolEvents: true, canCreateAnyClassEvent: true, canCreateParentMeetings: true, classes: undefined }).map((o) => o.kind)).toEqual(['SCHOOL', 'CLASS', 'PARENT_MEETING', 'PERSONAL']);
    expect(kindOptions({ canCreateSchoolEvents: false, canCreateAnyClassEvent: false, canCreateParentMeetings: true, classes: [{ id: CLASS_ID, name: '5A' }] }).map((o) => o.kind)).toEqual(['CLASS', 'PARENT_MEETING', 'PERSONAL']);
    expect(kindOptions({ canCreateSchoolEvents: false, canCreateAnyClassEvent: false, canCreateParentMeetings: false, classes: [] }).map((o) => o.kind)).toEqual(['PERSONAL']);
  });
  it('offers personal events to everybody, even without a school or capabilities', () => {
    expect(kindOptions(null)).toEqual([{ kind: 'PERSONAL', types: ['OTHER'] }]);
    expect(kindOptions(undefined).map((o) => o.kind)).toEqual(['PERSONAL']);
  });
  it('limits the types to the kind', () => {
    const options = kindOptions({ canCreateSchoolEvents: true, canCreateAnyClassEvent: true, canCreateParentMeetings: true, classes: undefined });
    expect(options.find((o) => o.kind === 'SCHOOL')!.types).toEqual(['SCHOOL_EVENT', 'EXAM', 'OTHER']);
    expect(options.find((o) => o.kind === 'CLASS')!.types).toEqual(['CLASS_EVENT', 'EXAM', 'OTHER']);
    expect(options.find((o) => o.kind === 'PARENT_MEETING')!.types).toEqual(['PARENT_MEETING']);
  });
  it('offers the time zones with the explicit ones first and only those this phone knows', () => {
    const zones = zoneChoices([NEW_YORK, ADDIS, null, 'Mars/Nowhere', NEW_YORK]);
    expect(zones.slice(0, 2)).toEqual([NEW_YORK, ADDIS]);
    expect(zones).not.toContain('Mars/Nowhere');
    expect(new Set(zones).size).toBe(zones.length);
  });
});

describe('the first choice of time', () => {
  it('is the next half hour at least an hour away, in the EVENT zone, and an hour long', () => {
    // 10:00 in Addis: an hour away is 11:00, already on the half hour.
    expect(defaultTimes(NOW, ADDIS)).toEqual({ startDate: '2026-11-26', startMinutes: 11 * 60, endDate: '2026-11-26', endMinutes: 12 * 60 });
    // The same instant in New York is 02:00: a different day-part, because the zone is the event's.
    expect(defaultTimes(NOW, NEW_YORK)).toEqual({ startDate: '2026-11-26', startMinutes: 3 * 60, endDate: '2026-11-26', endMinutes: 4 * 60 });
  });
  it('rolls over midnight', () => {
    const late = Date.UTC(2026, 10, 26, 20, 40); // 23:40 in Addis
    expect(defaultTimes(late, ADDIS)).toEqual({ startDate: '2026-11-27', startMinutes: 60, endDate: '2026-11-27', endMinutes: 120 });
  });
  it('uses 09:00 on the day the person was looking at', () => {
    expect(defaultTimes(NOW, ADDIS, '2026-12-07')).toEqual({ startDate: '2026-12-07', startMinutes: 540, endDate: '2026-12-07', endMinutes: 600 });
  });
  it('starts in the zone it is given and never anything else', () => {
    expect(newForm({ kind: 'SCHOOL', schoolId: SCHOOL_ID, timeZone: ADDIS, nowMs: NOW }).timeZone).toBe(ADDIS);
    expect(newForm({ kind: 'PERSONAL', schoolId: SCHOOL_ID, timeZone: NEW_YORK, nowMs: NOW })).toMatchObject({ timeZone: NEW_YORK, schoolId: null });
  });
});

describe('editing the state', () => {
  it('moves the end with the start, keeping the length', () => {
    const moved = withStart(form(), '2026-12-07', 14 * 60);
    expect(moved).toMatchObject({ startDate: '2026-12-07', startMinutes: 840, endDate: '2026-12-07', endMinutes: 900 });
    const over = withStart(form({ startDate: '2026-12-07', startMinutes: 22 * 60, endDate: '2026-12-08', endMinutes: 60 }), '2026-12-07', 23 * 60);
    expect(over).toMatchObject({ endDate: '2026-12-08', endMinutes: 120 });
  });
  it('keeps the number of days of an all-day event when its start moves', () => {
    const moved = withStart(form({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-09' }), '2026-12-14', 0);
    expect(moved).toMatchObject({ startDate: '2026-12-14', endDate: '2026-12-16' });
  });
  it('lets a weekly rule that only holds the start weekday follow the start', () => {
    const weekly = form({ startDate: '2026-12-07', repeat: { ...form().repeat, freq: 'WEEKLY', weekdays: [1] } });
    expect(withStart(weekly, '2026-12-09', weekly.startMinutes).repeat.weekdays).toEqual([3]);
  });
  it('switches to all-day and back', () => {
    const allDay = withAllDay(form({ startDate: '2026-12-07', endDate: '2026-12-07' }), true);
    expect(allDay.allDay).toBe(true);
    const back = withAllDay(allDay, false);
    expect(back.allDay).toBe(false);
    expect(back.endDate >= back.startDate).toBe(true);
  });
  it('keeps the typed clock times when the zone changes', () => {
    const changed = withZone(form({ startMinutes: 9 * 60 }), NEW_YORK);
    expect(changed.startMinutes).toBe(540);
    expect(changed.timeZone).toBe(NEW_YORK);
  });
  it('steps the time over midnight', () => {
    expect(stepTime('2026-12-07', 23 * 60 + 45, 1)).toEqual({ date: '2026-12-08', minutes: 0 });
    expect(stepTime('2026-12-07', 0, -1, 60)).toEqual({ date: '2026-12-06', minutes: 23 * 60 });
  });
  it('resets the audience and follows the zone when the kind changes', () => {
    const meeting = switchKind(form({ classId: CLASS_ID, repeat: { ...form().repeat, freq: 'DAILY' }, rsvpEnabled: true }), 'PARENT_MEETING', { schoolZone: ADDIS, userZone: NEW_YORK });
    expect(meeting).toMatchObject({ kind: 'PARENT_MEETING', eventType: 'PARENT_MEETING', classId: null, studentId: null, allDay: false, rsvpEnabled: false, timeZone: ADDIS });
    expect(meeting.repeat.freq).toBe('NONE');
    expect(switchKind(form(), 'PERSONAL', { schoolZone: ADDIS, userZone: NEW_YORK }).timeZone).toBe(NEW_YORK);
  });
  it('allows answers only on school and class events that do not repeat', () => {
    expect(canAskAnswers(form())).toBe(true);
    expect(canAskAnswers(form({ kind: 'PERSONAL' }))).toBe(false);
    expect(canAskAnswers(form({ kind: 'PARENT_MEETING' }))).toBe(false);
    expect(canAskAnswers(form({ repeat: { ...form().repeat, freq: 'WEEKLY' } }))).toBe(false);
  });
});

describe('validation', () => {
  const keys = (f: EventForm, original?: EventForm | null) => validateForm(f, NOW, original).map((issue) => issue.key.replace('mobile.calendar.form.problem.', ''));

  it('accepts a good form', () => {
    expect(keys(form())).toEqual([]);
    expect(keys(form({ kind: 'CLASS', classId: CLASS_ID, eventType: 'CLASS_EVENT' }))).toEqual([]);
  });
  it('wants a title of at most 200 characters, a description of 5000 and a place of 200', () => {
    expect(keys(form({ title: '   ' }))).toEqual(['titleRequired']);
    expect(keys(form({ title: 'x'.repeat(201) }))).toEqual(['titleTooLong']);
    expect(keys(form({ title: 'x'.repeat(200) }))).toEqual([]);
    expect(keys(form({ description: 'x'.repeat(5001) }))).toEqual(['descriptionTooLong']);
    expect(keys(form({ location: 'x'.repeat(201) }))).toEqual(['locationTooLong']);
    expect(issueFor(validateForm(form({ title: 'x'.repeat(201) }), NOW), 'title')?.values).toEqual({ max: 200 });
  });
  it('wants the class, the child and the school the kind needs', () => {
    expect(keys(form({ kind: 'CLASS', eventType: 'CLASS_EVENT' }))).toEqual(['classRequired']);
    expect(keys(form({ kind: 'PARENT_MEETING', eventType: 'PARENT_MEETING', startDate: '2026-12-07', endDate: '2026-12-07' }))).toEqual(['classRequired', 'studentRequired']);
    expect(keys(form({ schoolId: null }))).toEqual(['schoolRequired']);
    expect(keys(form({ kind: 'PERSONAL', schoolId: null, eventType: 'OTHER' }))).toEqual([]);
    expect(keys(form({ eventType: 'PARENT_MEETING' }))).toEqual(['typeInvalid']);
  });
  it('wants an end that is not before the start, in the event zone', () => {
    expect(keys(form({ startDate: '2026-12-07', startMinutes: 600, endDate: '2026-12-07', endMinutes: 540 }))).toEqual(['endBeforeStart']);
    expect(keys(form({ startDate: '2026-12-07', startMinutes: 600, endDate: '2026-12-07', endMinutes: 600 }))).toEqual([]);
    expect(keys(form({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-06' }))).toEqual(['endBeforeStart']);
    expect(keys(form({ allDay: true, startDate: '2026-12-07', endDate: '2027-12-08' }))).toEqual(['tooLong']);
    expect(keys(form({ allDay: true, startDate: '2026-12-07', endDate: '2027-12-07' }))).toEqual([]);
  });
  it('refuses years outside 1970 to 2200 and zones this phone does not know', () => {
    expect(keys(form({ startDate: '1969-12-31', endDate: '1969-12-31' }))).toEqual(['dateInvalid']);
    expect(keys(form({ startDate: '2201-01-01', endDate: '2201-01-01' }))).toEqual(['dateInvalid']);
    expect(keys(form({ timeZone: 'Mars/Nowhere' }))).toEqual(['timeZoneUnknown']);
  });
  it('checks the repeat and refuses answers on a repeating event', () => {
    const weekly = { ...form().repeat, freq: 'WEEKLY' as const, weekdays: [] };
    expect(keys(form({ repeat: weekly }))).toEqual(['repeat.weekdaysNone']);
    expect(keys(form({ rsvpEnabled: true, repeat: { ...form().repeat, freq: 'DAILY' } }))).toEqual(['rsvpNotWithRepeat']);
    expect(keys(form({ kind: 'PERSONAL', schoolId: null, eventType: 'OTHER', rsvpEnabled: true }))).toEqual(['rsvpNotWithRepeat']);
  });
  it('wants a parent meeting to be timed and in the future', () => {
    const meeting = form({ kind: 'PARENT_MEETING', eventType: 'PARENT_MEETING', classId: CLASS_ID, studentId: STUDENT_ID, startDate: '2026-11-26', startMinutes: 9 * 60, endDate: '2026-11-26', endMinutes: 10 * 60 });
    expect(keys(meeting)).toEqual(['meetingPast']); // 09:00 Addis is 06:00Z, before NOW (07:00Z)
    expect(keys({ ...meeting, startDate: '2026-12-07', endDate: '2026-12-07' })).toEqual([]);
    expect(keys({ ...meeting, startDate: '2026-12-07', endDate: '2026-12-07', allDay: true })).toEqual(['meetingTimed']);
  });
  it('does not ask an unchanged past meeting to be in the future when only its text is edited', () => {
    const original = form({ kind: 'PARENT_MEETING', eventType: 'PARENT_MEETING', mode: 'edit', startDate: '2026-11-26', startMinutes: 9 * 60, endDate: '2026-11-26', endMinutes: 10 * 60 });
    expect(keys({ ...original, title: 'Report talk' }, original)).toEqual([]);
    expect(keys({ ...original, startMinutes: 9 * 60 + 15 }, original)).toEqual(['meetingPast']);
  });
  it('limits how far one occurrence may move', () => {
    const base = form({ mode: 'occurrence', startDate: '2026-12-07', startMinutes: 540, endDate: '2026-12-07', endMinutes: 600 });
    expect(keys({ ...base, startDate: '2026-12-14', endDate: '2026-12-14' }, base)).toEqual([]);
    expect(keys({ ...base, startDate: '2026-12-15', endDate: '2026-12-15' }, base)).toEqual(['occurrenceShift']);
    expect(keys({ ...base, startDate: '2026-11-29', endDate: '2026-11-29' }, base)).toEqual(['occurrenceShift']);
  });
});

describe('the request to create', () => {
  it('sends the zone and the instants of the wall-clock times in it', () => {
    const request = createRequest(form({ startDate: '2026-12-07', startMinutes: 9 * 60, endDate: '2026-12-07', endMinutes: 10 * 60 + 30, location: ' Hall ', description: '' }));
    expect(request).toEqual({
      title: 'Sports day',
      timeZone: ADDIS,
      allDay: false,
      startAt: '2026-12-07T06:00:00.000Z',
      endAt: '2026-12-07T07:30:00.000Z',
      location: 'Hall',
      scope: 'SCHOOL',
      eventType: 'SCHOOL_EVENT',
    });
  });
  it('is different for the same wall-clock times in another zone', () => {
    const request = createRequest(form({ timeZone: NEW_YORK, startDate: '2026-12-07', startMinutes: 9 * 60, endDate: '2026-12-07', endMinutes: 10 * 60 }));
    expect(request.startAt).toBe('2026-12-07T14:00:00.000Z');
    expect(request.timeZone).toBe(NEW_YORK);
  });
  it('sends dates, not instants, for an all-day event', () => {
    const request = createRequest(form({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-09' }));
    expect(request).toMatchObject({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-09', timeZone: ADDIS });
    expect(request).not.toHaveProperty('startAt');
    expect(request).not.toHaveProperty('endAt');
  });
  it('sends the class, the repeat and the answers flag', () => {
    const request = createRequest(
      form({ kind: 'CLASS', eventType: 'EXAM', classId: CLASS_ID, rsvpEnabled: true, startDate: '2026-12-07', repeat: { ...form().repeat, freq: 'WEEKLY', weekdays: [1], end: 'count', count: 6 } }),
    );
    expect(request).toMatchObject({ scope: 'CLASS', classId: CLASS_ID, eventType: 'EXAM', recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO;COUNT=6' });
    // A repeating event never asks for answers, even if the switch was on.
    expect(request).not.toHaveProperty('rsvpEnabled');
    expect(createRequest(form({ kind: 'CLASS', eventType: 'CLASS_EVENT', classId: CLASS_ID, rsvpEnabled: true })).rsvpEnabled).toBe(true);
  });
  it('sends a parent meeting with the child and no scope, rule or answers flag', () => {
    const request = createRequest(
      form({ kind: 'PARENT_MEETING', eventType: 'PARENT_MEETING', classId: CLASS_ID, studentId: STUDENT_ID, rsvpEnabled: true, repeat: { ...form().repeat, freq: 'DAILY' } }),
    );
    expect(request).toMatchObject({ eventType: 'PARENT_MEETING', regardingStudentId: STUDENT_ID });
    for (const key of ['scope', 'classId', 'recurrenceRule', 'rsvpEnabled']) expect(request).not.toHaveProperty(key);
  });
  it('sends a personal event as scope PERSONAL in its own zone', () => {
    const request = createRequest(form({ kind: 'PERSONAL', schoolId: null, eventType: 'OTHER', timeZone: NEW_YORK }));
    expect(request).toMatchObject({ scope: 'PERSONAL', eventType: 'OTHER', timeZone: NEW_YORK });
  });
});

describe('editing an existing event', () => {
  const EVENT: CalendarEventView = {
    id: CLASS_ID,
    schoolId: SCHOOL_ID,
    scope: 'CLASS',
    classId: CLASS_ID,
    eventType: 'CLASS_EVENT',
    title: 'Choir',
    description: 'Bring water',
    location: 'Hall',
    timeZone: ADDIS,
    status: 'SCHEDULED',
    allDay: false,
    startAt: '2026-12-07T06:00:00Z',
    endAt: '2026-12-07T07:30:00Z',
    recurring: true,
    recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO',
    rsvpEnabled: false,
    overrides: [{ occurrenceId: '20261214T060000Z', status: 'CANCELLED' }],
    capabilities: { canEdit: true, canCancel: true, canDelete: false },
  };
  const original = formFromEvent(EVENT);

  it('reads the wall-clock times back in the event zone', () => {
    expect(original).toMatchObject({ mode: 'edit', kind: 'CLASS', startDate: '2026-12-07', startMinutes: 540, endDate: '2026-12-07', endMinutes: 630, timeZone: ADDIS, title: 'Choir', description: 'Bring water' });
    expect(buildRule(original.repeat)).toBe('FREQ=WEEKLY;BYDAY=MO');
  });
  it('sends nothing for an unchanged form', () => {
    expect(isEmptyRequest(updateRequest(original, original))).toBe(true);
    expect(scheduleDiffers(original, original)).toBe(false);
  });
  it('sends only the text that changed, without touching the schedule', () => {
    const request = updateRequest(original, { ...original, title: 'Choir practice', description: '' });
    expect(request).toEqual({ title: 'Choir practice', description: '' });
    expect(changeWarnings(EVENT, original, { ...original, title: 'Choir practice' })).toEqual({ clearsExceptions: false, resetsAnswers: false, notifies: false });
  });
  it('sends both ends of a new time and warns that exceptions are cleared', () => {
    const next = { ...original, startMinutes: 10 * 60, endMinutes: 11 * 60 };
    expect(updateRequest(original, next)).toEqual({ allDay: false, startAt: '2026-12-07T07:00:00.000Z', endAt: '2026-12-07T08:00:00.000Z' });
    const warnings = changeWarnings(EVENT, original, next);
    expect(warnings).toEqual({ clearsExceptions: true, resetsAnswers: false, notifies: true });
    expect(hasWarnings(warnings)).toBe(true);
  });
  it('warns that answers are asked again when the time of an event with answers moves', () => {
    const asking: CalendarEventView = { ...EVENT, recurring: false, recurrenceRule: undefined, overrides: [], rsvpEnabled: true, rsvp: { enabled: true, canAnswer: true } };
    const form = formFromEvent(asking);
    expect(changeWarnings(asking, form, { ...form, startDate: '2026-12-08', endDate: '2026-12-08' })).toEqual({ clearsExceptions: false, resetsAnswers: true, notifies: true });
    expect(changeWarnings(asking, form, { ...form, location: 'Library' })).toEqual({ clearsExceptions: false, resetsAnswers: false, notifies: true });
  });
  it('sends the zone with the times when only the zone changes, because the wall clock is what was typed', () => {
    const request = updateRequest(original, withZone(original, NEW_YORK));
    expect(request.timeZone).toBe(NEW_YORK);
    expect(request.startAt).toBe('2026-12-07T14:00:00.000Z');
  });
  it('sends an empty rule to stop a repeat, and the new rule to change it', () => {
    expect(updateRequest(original, { ...original, repeat: { ...original.repeat, freq: 'NONE' } })).toEqual({ recurrenceRule: '' });
    expect(updateRequest(original, { ...original, repeat: { ...original.repeat, interval: 2 } })).toEqual({ recurrenceRule: 'FREQ=WEEKLY;INTERVAL=2;BYDAY=MO' });
  });
  it('sends the answers flag only when it changes and the event can ask', () => {
    const one = formFromEvent({ ...EVENT, recurring: false, recurrenceRule: undefined, overrides: [] });
    expect(updateRequest(one, { ...one, rsvpEnabled: true })).toEqual({ rsvpEnabled: true });
    expect(isEmptyRequest(updateRequest(one, one))).toBe(true);
  });
  it('changes one occurrence: only its own text and time', () => {
    const occurrence = formFromOccurrence(EVENT, '20261221T060000Z');
    expect(occurrence).toMatchObject({ mode: 'occurrence', startDate: '2026-12-21', startMinutes: 540, endMinutes: 630 });
    expect(isEmptyRequest(occurrenceRequest(occurrence, occurrence))).toBe(true);
    expect(occurrenceRequest(occurrence, { ...occurrence, title: 'Choir (hall)', location: 'Hall 2' })).toEqual({ title: 'Choir (hall)', location: 'Hall 2' });
    expect(occurrenceRequest(occurrence, withEnd(withStart(occurrence, '2026-12-22', 600), '2026-12-22', 690))).toEqual({
      startAt: '2026-12-22T07:00:00.000Z',
      endAt: '2026-12-22T08:30:00.000Z',
    });
  });
  it('reads an all-day occurrence by dates', () => {
    const allDay: CalendarEventView = { ...EVENT, allDay: true, startDate: '2026-12-07', endDate: '2026-12-08', startAt: '2026-12-06T21:00:00Z', endAt: '2026-12-08T21:00:00Z' };
    const occurrence = formFromOccurrence(allDay, '20261220T210000Z');
    expect(occurrence).toMatchObject({ allDay: true, startDate: '2026-12-21', endDate: '2026-12-22' });
    expect(occurrenceRequest(occurrence, { ...occurrence, startDate: '2026-12-22', endDate: '2026-12-23' })).toEqual({ startDate: '2026-12-22', endDate: '2026-12-23' });
  });
});
