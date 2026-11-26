import { describe, expect, it } from 'vitest';

import { routeForPath } from '@/lib/notifications/routes';

import { bucketItems, daysWithEntries, entryTime } from './bucket';
import { eventActions, rsvpModel } from './event-model';
import { createRequest, formFromEvent, formFromOccurrence, kindOptions, newForm, occurrenceRequest, updateRequest, withEnd, withStart } from './form';
import { rangeFor } from './grid';
import { isCalendarNotification } from './notifications';
import { occurrenceTimes, splitItemId } from './occurrence';
import { describeRepeat, parseRule } from './recurrence';
import { fetchWindow } from './window';
import type { CalendarEventView, CalendarItem } from './types';
import payloads from './fixtures/payloads.json';

/**
 * Real payloads of the API (recorded from the M11a backend by driving every flow of the screens over HTTP, see the M11c note in
 * the implementation plan) run through the app's own rules: the month grid, the day buckets, the capabilities, the forms and the
 * answers. When the API changes shape, this is where the app notices.
 */

const P = payloads as unknown as {
  capabilities: { admin: Parameters<typeof kindOptions>[0]; lead: Parameters<typeof kindOptions>[0]; assistant: Parameters<typeof kindOptions>[0] };
  unionGuardianDecember: { items: CalendarItem[]; truncated: boolean };
  unionGuardianSeptember: { items: CalendarItem[] };
  unionAfterExceptions: { items: CalendarItem[] };
  choirViewLead: CalendarEventView;
  choirViewWithOverrides: CalendarEventView;
  meetingViewOrganiser: CalendarEventView;
  meetingViewGuardian: CalendarEventView;
  meetingViewOrganiserAnswered: CalendarEventView;
  sportsDayView: CalendarEventView;
  invitationNotification: { typeCode: string; schoolId: string; title: string; body: string; data: Record<string, string> };
  shiftRefusedAm: { code: string; detail: string };
  rsvpOnRepeating: { code: string; status: number };
};

const ADDIS = 'Africa/Addis_Ababa';
const DECEMBER = rangeFor('month', '2026-12-10', 'gregorian', 1);

describe('the December window of a guardian', () => {
  const { items } = P.unionGuardianDecember;
  const days = bucketItems(items, DECEMBER);

  it('asks for a window the API accepts', () => {
    const window = fetchWindow(DECEMBER)!;
    expect((Date.parse(window.to) - Date.parse(window.from)) / 86_400_000).toBeLessThan(366);
    expect(P.unionGuardianDecember.truncated).toBe(false);
  });
  it('puts the repeating class event on its occurrence days in the event zone', () => {
    const choir = [...days.entries()].filter(([, entries]) => entries.some((entry) => entry.item.title === 'Choir')).map(([date]) => date);
    expect(choir).toEqual(['2026-12-07', '2026-12-09', '2026-12-14', '2026-12-16', '2026-12-21', '2026-12-23']);
    const first = days.get('2026-12-07')!.find((entry) => entry.item.title === 'Choir')!.item;
    expect(first).toMatchObject({ recurring: true, kind: 'CLASS_EVENT', timeZone: ADDIS, startAt: '2026-12-07T06:00:00Z' });
    expect(splitItemId(first.id)).toEqual({ eventId: first.eventId, occurrenceId: '20261207T060000Z' });
  });
  it('puts the all-day school event on its two inclusive dates', () => {
    const sports = [...days.entries()].filter(([, entries]) => entries.some((entry) => entry.item.title === 'Sports day')).map(([date]) => date);
    expect(sports).toEqual(['2026-12-02', '2026-12-03']);
    const item = days.get('2026-12-02')![0]!.item;
    expect(item).toMatchObject({ allDay: true, startDate: '2026-12-02', endDate: '2026-12-03' });
    // endAt is end-exclusive: the start of the 4th day in Addis, not part of the event
    expect(item.endAt).toBe('2026-12-03T21:00:00Z');
  });
  it('shows the school break of the school as a holiday on its three days, with the name in every language', () => {
    const holiday = days.get('2026-12-25')!.find((entry) => entry.item.source === 'HOLIDAY')!;
    expect(holiday.item).toMatchObject({ title: 'Winter break', startDate: '2026-12-24', endDate: '2026-12-26', allDay: true });
    expect(holiday.item.titleI18n).toMatchObject({ en: 'Winter break', am: 'የክረምት እረፍት' });
    expect(holiday).toMatchObject({ dayIndex: 1, dayCount: 3 });
    expect(daysWithEntries(days).filter((date) => days.get(date)!.some((entry) => entry.item.source === 'HOLIDAY'))).toEqual(['2026-12-24', '2026-12-25', '2026-12-26']);
  });
  it('sorts each day with the all-day items first and shows a timed item by its time', () => {
    const entries = days.get('2026-12-07')!;
    expect(entries.map((entry) => entry.item.title)).toContain('Choir');
    expect(entryTime(entries.find((entry) => entry.item.title === 'Choir')!)).toMatchObject({ kind: 'range', startMs: Date.parse('2026-12-07T06:00:00Z'), endMs: Date.parse('2026-12-07T07:30:00Z') });
  });
});

describe('academic dates', () => {
  it('shows the start of the school year and of the term as one-day items', () => {
    const days = bucketItems(P.unionGuardianSeptember.items, rangeFor('month', '2026-09-15', 'gregorian', 1));
    const entries = days.get('2026-09-11')!;
    expect(entries.map((entry) => entry.item.kind).sort()).toEqual(['PERIOD_START', 'YEAR_START']);
    expect(entries.every((entry) => entry.item.source === 'ACADEMIC_PERIOD' && entry.item.allDay)).toBe(true);
  });
});

describe('exceptions of a repeating event in the union', () => {
  const days = bucketItems(P.unionAfterExceptions.items, DECEMBER);
  it('keeps the cancelled occurrence on its day, marked, and moves the changed one to its new day', () => {
    const cancelled = days.get('2026-12-09')!.find((entry) => entry.item.title === 'Choir')!;
    expect(cancelled.item.status).toBe('CANCELLED');
    const moved = days.get('2026-12-15')!.find((entry) => entry.item.title === 'Choir (hall)')!;
    expect(moved.item).toMatchObject({ modified: true, id: expect.stringContaining('@20261214T060000Z') });
    expect(days.get('2026-12-14')!.some((entry) => entry.item.title.startsWith('Choir'))).toBe(false);
  });
  it('reads the same exceptions from the event itself', () => {
    const view = P.choirViewWithOverrides;
    expect(occurrenceTimes(view, '20261209T060000Z')).toMatchObject({ cancelled: true, cancellationReason: 'Teacher away' });
    const moved = occurrenceTimes(view, '20261214T060000Z');
    expect(moved.modified).toBe(true);
    expect(new Date(moved.startMs!).toISOString()).toBe('2026-12-15T11:00:00.000Z');
    expect(occurrenceTimes(view, '20261221T060000Z')).toMatchObject({ cancelled: false, modified: false });
  });
  it('describes the repeat in words and reads the rule back into the form', () => {
    const view = P.choirViewLead;
    const repeat = parseRule(view.recurrenceRule, '2026-12-07', ADDIS)!;
    const words = describeRepeat(repeat, { t: (key, values) => `${key} ${JSON.stringify(values ?? {})}`, weekdayName: (day) => `d${day}`, formatDate: (date) => date });
    expect(words).toContain('mobile.calendar.repeat.withCount');
    expect(formFromEvent(view)).toMatchObject({ kind: 'CLASS', repeat: { freq: 'WEEKLY', weekdays: [1, 3], end: 'count', count: 6 }, startMinutes: 540, endMinutes: 630 });
  });
});

describe('what each person may do, from the capabilities the API sent', () => {
  it('offers a school manager school and class events and a class teacher class events and parent meetings', () => {
    expect(kindOptions(P.capabilities.admin).map((option) => option.kind)).toContain('SCHOOL');
    expect(kindOptions(P.capabilities.lead).map((option) => option.kind)).toEqual(['CLASS', 'PARENT_MEETING', 'PERSONAL']);
    expect(kindOptions(P.capabilities.assistant).map((option) => option.kind)).toEqual(['PERSONAL']);
  });
  it('offers the teacher editing and cancelling and a guardian nothing', () => {
    expect(eventActions(P.choirViewLead, '20261207T060000Z')).toMatchObject({ edit: true, cancel: true, editOccurrence: true, cancelOccurrence: true, delete: false });
    expect(eventActions(P.meetingViewGuardian, null)).toMatchObject({ edit: false, cancel: false, delete: false });
  });
});

describe('a parent meeting', () => {
  it('shows its organiser each guardian and their answer, and the guardian only their own', () => {
    const organiser = rsvpModel(P.meetingViewOrganiser);
    expect(organiser.participants).toHaveLength(2);
    expect(organiser.participants!.every((p) => p.status === 'PENDING')).toBe(true);
    const guardian = rsvpModel(P.meetingViewGuardian);
    expect(guardian).toMatchObject({ enabled: true, canAnswer: true, mine: 'PENDING', participants: null, counts: null });
    expect(P.meetingViewGuardian).toMatchObject({ scope: 'INVITEES', eventType: 'PARENT_MEETING' });
    const answered = rsvpModel(P.meetingViewOrganiserAnswered);
    expect(answered.counts).toEqual({ yes: 1, no: 0, maybe: 0, pending: 1 });
  });
  it('is read back into a form that keeps the child and sends a reschedule as the new instants', () => {
    const form = formFromEvent(P.meetingViewOrganiser);
    expect(form).toMatchObject({ kind: 'PARENT_MEETING', startMinutes: 15 * 60, endMinutes: 15 * 60 + 30, timeZone: ADDIS });
    expect(form.studentId).toBe(P.meetingViewOrganiser.regardingStudentId);
    expect(updateRequest(form, { ...form, startMinutes: 16 * 60, endMinutes: 16 * 60 + 30 })).toEqual({ allDay: false, startAt: '2026-12-11T13:00:00.000Z', endAt: '2026-12-11T13:30:00.000Z' });
  });
  it('is sent without scope or class, with the child', () => {
    const form = { ...newForm({ kind: 'PARENT_MEETING', schoolId: 's', timeZone: ADDIS, nowMs: Date.parse('2026-11-26T07:00:00Z'), classId: 'c', studentId: 'child' }), title: 'Talk' };
    const request = createRequest(form);
    expect(request).toMatchObject({ eventType: 'PARENT_MEETING', regardingStudentId: 'child', timeZone: ADDIS });
    expect(request).not.toHaveProperty('scope');
  });
});

describe('occurrence edits', () => {
  it('builds the request the API accepted for moving and renaming one occurrence', () => {
    const occurrence = formFromOccurrence(P.choirViewLead, '20261214T060000Z');
    const moved = withEnd(withStart({ ...occurrence, title: 'Choir (hall)' }, '2026-12-15', 14 * 60), '2026-12-15', 15 * 60);
    expect(occurrenceRequest(occurrence, moved)).toEqual({ title: 'Choir (hall)', startAt: '2026-12-15T11:00:00.000Z', endAt: '2026-12-15T12:00:00.000Z' });
  });
});

describe('errors of the API', () => {
  it('carries a localized detail and a stable code the app shows and maps', () => {
    expect(P.shiftRefusedAm.code).toBe('event.occurrence_shift_too_large');
    expect(/[ሀ-፿]/.test(P.shiftRefusedAm.detail)).toBe(true);
    expect(P.rsvpOnRepeating.code).toBe('event.rsvp_disabled');
  });
});

describe('the invitation notification', () => {
  const note = P.invitationNotification;
  it('is a calendar notification that opens the event through the path mapper', () => {
    expect(isCalendarNotification(note)).toBe(true);
    const eventId = note.data.eventId!;
    expect(note.data.path).toBe(`/calendar/events/${eventId}`);
    expect(routeForPath(note.data.path, '/(guardian)/home', { ...note.data, schoolId: note.schoolId })).toBe(`/events/${eventId}?schoolId=${note.schoolId}`);
  });
  it('never names the child in its words (the meeting title does, the notification must not)', () => {
    expect(`${note.title} ${note.body}`).not.toMatch(/Abel/);
    expect(P.meetingViewGuardian.title).toMatch(/Abel/);
  });
});

describe('the all-day school event as the form sends it back', () => {
  it('reads dates and zone back unchanged, so an untouched edit sends nothing', () => {
    const form = formFromEvent(P.sportsDayView);
    expect(form).toMatchObject({ allDay: true, startDate: '2026-12-02', endDate: '2026-12-03', timeZone: ADDIS });
    expect(updateRequest(form, form)).toEqual({});
  });
});
