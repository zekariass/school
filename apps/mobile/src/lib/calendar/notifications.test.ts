import { describe, expect, it } from 'vitest';

import { routeForPath } from '@/lib/notifications/routes';

import { CALENDAR_NOTIFICATION_TYPES, isCalendarNotification } from './notifications';

const EVENT = '5b3b9f5e-0f3c-4a58-9c63-3f1a4e2d7a10';
const SCHOOL = '7d5d1b70-2b5e-4c7a-9e85-5b3c6a4f9c32';
const HOME = '/(guardian)/home';

describe('which notifications are about the calendar', () => {
  it('knows the four types', () => {
    expect(CALENDAR_NOTIFICATION_TYPES).toEqual(['EVENT_INVITATION', 'EVENT_UPDATED', 'EVENT_CANCELLED', 'UPCOMING_EVENT']);
    for (const typeCode of CALENDAR_NOTIFICATION_TYPES) expect(isCalendarNotification({ typeCode })).toBe(true);
  });
  it('knows them by their path too', () => {
    expect(isCalendarNotification({ typeCode: 'SOMETHING', data: { path: `/calendar/events/${EVENT}` } })).toBe(true);
    expect(isCalendarNotification({ typeCode: 'NEW_POST', data: { path: `/feed/posts/${EVENT}` } })).toBe(false);
    expect(isCalendarNotification({})).toBe(false);
  });
});

describe('opening an event from a notification', () => {
  it('opens the event for every role, with the school and occurrence the push names', () => {
    expect(routeForPath(`/calendar/events/${EVENT}`, HOME)).toBe(`/events/${EVENT}`);
    expect(routeForPath(`/calendar/events/${EVENT}`, '/(teacher)/classes', { eventId: EVENT, schoolId: SCHOOL })).toBe(`/events/${EVENT}?schoolId=${SCHOOL}`);
    expect(routeForPath(`/calendar/events/${EVENT}`, '/(student)/home', { body: { schoolId: SCHOOL } })).toBe(`/events/${EVENT}?schoolId=${SCHOOL}`);
    expect(routeForPath(`/calendar/events/${EVENT}`, HOME, { schoolId: SCHOOL, occurrenceId: '20261214T060000Z' })).toBe(`/events/${EVENT}?schoolId=${SCHOOL}&occurrenceId=20261214T060000Z`);
  });
  it('reads the data of a raw APNs body, or of JSON text', () => {
    expect(routeForPath(`/calendar/events/${EVENT}`, HOME, JSON.stringify({ schoolId: SCHOOL }))).toBe(`/events/${EVENT}?schoolId=${SCHOOL}`);
  });
  it('never follows a bad id, a bad parameter, an extra path part or a missing role', () => {
    expect(routeForPath('/calendar/events/not-an-id', HOME)).toBeNull();
    expect(routeForPath(`/calendar/events/${EVENT}/../x`, HOME)).toBeNull();
    expect(routeForPath(`/calendar/events/${EVENT}`, null)).toBeNull();
    expect(routeForPath(`/calendar/events/${EVENT}`, HOME, { schoolId: '../../x', occurrenceId: 'x?y=1' })).toBe(`/events/${EVENT}`);
    expect(routeForPath('/calendar/events', HOME)).toBeNull();
  });
});
