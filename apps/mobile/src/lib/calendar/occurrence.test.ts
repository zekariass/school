import { describe, expect, it } from 'vitest';

import { eventActions, isParentMeeting, rsvpLabelKey, rsvpModel } from './event-model';
import { isOccurrenceId, occurrenceIdOf, occurrenceInstant, occurrenceText, occurrenceTimes, splitItemId } from './occurrence';
import type { CalendarEventView } from './types';

const EVENT = '5b3b9f5e-0f3c-4a58-9c63-3f1a4e2d7a10';
const ADDIS = 'Africa/Addis_Ababa';

function view(over: Partial<CalendarEventView>): CalendarEventView {
  return {
    id: EVENT,
    scope: 'CLASS',
    eventType: 'CLASS_EVENT',
    title: 'Choir',
    timeZone: ADDIS,
    status: 'SCHEDULED',
    allDay: false,
    startAt: '2026-12-07T06:00:00Z',
    endAt: '2026-12-07T07:30:00Z',
    recurring: true,
    recurrenceRule: 'FREQ=WEEKLY;BYDAY=MO',
    rsvpEnabled: false,
    overrides: [],
    capabilities: { canEdit: true, canCancel: true, canDelete: false },
    ...over,
  };
}

describe('occurrence ids', () => {
  it('recognises the instant ids of the API', () => {
    expect(isOccurrenceId('20261207T060000Z')).toBe(true);
    expect(isOccurrenceId('20261307T060000Z')).toBe(false);
    expect(isOccurrenceId('2026-12-07T06:00:00Z')).toBe(false);
    expect(isOccurrenceId(undefined)).toBe(false);
    expect(occurrenceInstant('20261207T060000Z')).toBe(Date.UTC(2026, 11, 7, 6, 0, 0));
  });
  it('writes the id of an instant', () => {
    expect(occurrenceIdOf(Date.UTC(2026, 11, 7, 6, 0, 0))).toBe('20261207T060000Z');
    expect(occurrenceInstant(occurrenceIdOf(Date.UTC(2027, 0, 1, 23, 59, 59)))).toBe(Date.UTC(2027, 0, 1, 23, 59, 59));
  });
  it('splits the id of an item', () => {
    expect(splitItemId(EVENT)).toEqual({ eventId: EVENT, occurrenceId: null });
    expect(splitItemId(`${EVENT}@20261207T060000Z`)).toEqual({ eventId: EVENT, occurrenceId: '20261207T060000Z' });
    expect(splitItemId('HOLIDAY:123')).toBeNull();
    expect(splitItemId('PERIOD:abc:START')).toBeNull();
    expect(splitItemId(`${EVENT}@nonsense`)).toBeNull();
    expect(splitItemId(`${EVENT}@20261207T060000Z@x`)).toBeNull();
  });
});

describe('when an occurrence happens', () => {
  it('shifts the series to the occurrence and keeps its length', () => {
    const times = occurrenceTimes(view({}), '20261214T060000Z');
    expect(new Date(times.startMs!).toISOString()).toBe('2026-12-14T06:00:00.000Z');
    expect(new Date(times.endMs!).toISOString()).toBe('2026-12-14T07:30:00.000Z');
    expect(times).toMatchObject({ cancelled: false, modified: false, allDay: false });
  });
  it('shifts all-day series by dates, in the zone of the event', () => {
    const allDay = view({ allDay: true, startDate: '2026-12-07', endDate: '2026-12-08', startAt: '2026-12-06T21:00:00Z', endAt: '2026-12-08T21:00:00Z' });
    const times = occurrenceTimes(allDay, '20261213T210000Z'); // the 14th starts at 21:00Z the day before in Addis
    expect(times).toMatchObject({ allDay: true, startDate: '2026-12-14', endDate: '2026-12-15' });
  });
  it('uses the exception of a moved occurrence, found by the id it had in the series', () => {
    const moved = view({
      overrides: [{ occurrenceId: '20261214T060000Z', occurrenceStart: '2026-12-14T06:00:00Z', status: 'MODIFIED', title: 'Choir (hall)', startAt: '2026-12-15T08:00:00Z', endAt: '2026-12-15T09:00:00Z' }],
    });
    const times = occurrenceTimes(moved, '20261214T060000Z');
    expect(times.modified).toBe(true);
    expect(new Date(times.startMs!).toISOString()).toBe('2026-12-15T08:00:00.000Z');
    expect(occurrenceText(moved, times.override).title).toBe('Choir (hall)');
    expect(occurrenceText(moved, null).title).toBe('Choir');
  });
  it('marks a cancelled occurrence, with its reason', () => {
    const cancelled = view({ overrides: [{ occurrenceId: '20261214T060000Z', status: 'CANCELLED', reason: 'Holiday' }] });
    expect(occurrenceTimes(cancelled, '20261214T060000Z')).toMatchObject({ cancelled: true, cancellationReason: 'Holiday' });
    expect(occurrenceTimes(cancelled, '20261221T060000Z').cancelled).toBe(false);
  });
  it('shows the event itself when there is no occurrence or it does not repeat', () => {
    const one = view({ recurring: false });
    expect(occurrenceTimes(one, '20261214T060000Z').startMs).toBe(Date.parse('2026-12-07T06:00:00Z'));
    expect(occurrenceTimes(view({}), null).startMs).toBe(Date.parse('2026-12-07T06:00:00Z'));
    expect(occurrenceTimes(view({ status: 'CANCELLED' }), null).cancelled).toBe(true);
  });
});

describe('what the caller may do, from the API capabilities', () => {
  it('offers the actions the API allows', () => {
    const actions = eventActions(view({}), '20261214T060000Z');
    expect(actions).toMatchObject({ edit: true, cancel: true, delete: false, editOccurrence: true, cancelOccurrence: true, restoreOccurrence: false, recurring: true });
  });
  it('offers nothing to somebody who may only look', () => {
    const actions = eventActions(view({ capabilities: { canEdit: false, canCancel: false, canDelete: false } }), '20261214T060000Z');
    expect(actions).toMatchObject({ edit: false, cancel: false, delete: false, editOccurrence: false, cancelOccurrence: false });
    expect(eventActions(view({ capabilities: undefined }), null).edit).toBe(false);
  });
  it('offers only whole-series actions without an occurrence', () => {
    const actions = eventActions(view({}), null);
    expect(actions).toMatchObject({ edit: true, cancel: true, editOccurrence: false, cancelOccurrence: false });
  });
  it('lets a cancelled event be deleted, not changed or cancelled again', () => {
    const actions = eventActions(view({ status: 'CANCELLED', capabilities: { canEdit: true, canCancel: true, canDelete: true } }), null);
    expect(actions).toMatchObject({ cancelled: true, edit: false, cancel: false, delete: true });
  });
  it('lets an exception be taken away', () => {
    const cancelled = view({ overrides: [{ occurrenceId: '20261214T060000Z', status: 'CANCELLED' }] });
    expect(eventActions(cancelled, '20261214T060000Z')).toMatchObject({ occurrenceCancelled: true, restoreOccurrence: true, editOccurrence: false, cancelOccurrence: false });
  });
});

describe('the answers of an event', () => {
  it('lets a guardian invited to a meeting answer', () => {
    const meeting = view({ recurring: false, scope: 'INVITEES', eventType: 'PARENT_MEETING', rsvpEnabled: true, rsvp: { enabled: true, mine: 'PENDING', canAnswer: true } });
    expect(rsvpModel(meeting)).toMatchObject({ enabled: true, canAnswer: true, mine: 'PENDING', counts: null, participants: null });
    expect(isParentMeeting(meeting)).toBe(true);
  });
  it('shows the organiser the totals and the answers, and nobody else', () => {
    const organiser = view({
      recurring: false,
      rsvp: {
        enabled: true,
        canAnswer: false,
        counts: { yes: 1, no: 0, maybe: 1, pending: 2 },
        participants: [{ userId: 'u1', displayName: 'Abebe Kebede', status: 'YES', respondedAt: '2026-12-01T10:00:00Z' }],
      },
    });
    expect(rsvpModel(organiser)).toMatchObject({ counts: { yes: 1, no: 0, maybe: 1, pending: 2 }, participants: [{ userId: 'u1', name: 'Abebe Kebede', status: 'YES' }], mine: null });
    expect(rsvpModel(view({ recurring: false, rsvp: { enabled: true, canAnswer: true, mine: 'YES' } })).participants).toBeNull();
  });
  it('does not let anybody answer a cancelled event or one that no longer takes answers', () => {
    expect(rsvpModel(view({ recurring: false, status: 'CANCELLED', rsvp: { enabled: true, canAnswer: true, mine: 'YES' } })).canAnswer).toBe(false);
    expect(rsvpModel(view({ recurring: false, rsvp: { enabled: true, canAnswer: false, mine: 'YES' } })).canAnswer).toBe(false);
  });
  it('explains that repeating events take no answers, except personal ones', () => {
    expect(rsvpModel(view({})).recurringNote).toBe(true);
    expect(rsvpModel(view({ scope: 'PERSONAL' })).recurringNote).toBe(false);
    expect(rsvpModel(view({ recurring: false })).recurringNote).toBe(false);
  });
  it('names every answer', () => {
    expect(rsvpLabelKey('YES')).toBe('mobile.calendar.rsvp.yes');
    expect(rsvpLabelKey('NO')).toBe('mobile.calendar.rsvp.no');
    expect(rsvpLabelKey('MAYBE')).toBe('mobile.calendar.rsvp.maybe');
    expect(rsvpLabelKey('PENDING')).toBe('mobile.calendar.rsvp.pending');
  });
});
