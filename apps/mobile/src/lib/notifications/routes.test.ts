import { describe, expect, it } from 'vitest';

import { notificationIdFromData, pathFromNotificationData, routeForPath } from './routes';

const HOME = '/(guardian)/home';

describe('routeForPath', () => {
  it('opens known screens', () => {
    expect(routeForPath('/notifications', HOME)).toBe('/notifications');
    expect(routeForPath('/settings', HOME)).toBe('/settings');
    expect(routeForPath('/', HOME)).toBe(HOME);
  });
  it('opens the guardian screens', () => {
    expect(routeForPath('/children', HOME)).toBe(HOME);
    expect(routeForPath('/consents', HOME)).toBe('/consent');
  });
  it('opens the points of a student, and the home of anybody else', () => {
    expect(routeForPath('/points', '/(student)/home')).toBe('/(student)/points');
    expect(routeForPath('/points', '/(teacher)/classes')).toBe('/(teacher)/classes');
    expect(routeForPath('/points', null)).toBeNull();
  });
  it('opens the absence-report inbox of the class for a teacher (ABSENCE_REPORTED)', () => {
    const classId = '0f8fad5b-d9cb-469f-a165-70867728950e';
    const teacher = '/(teacher)/classes';
    expect(routeForPath(`/classes/${classId}/attendance`, teacher, { classId })).toBe(`/class/${classId}/absences`);
    // Anyone else stays on their home; a path that is not a class id is not followed.
    expect(routeForPath(`/classes/${classId}/attendance`, HOME)).toBe(HOME);
    expect(routeForPath('/classes/not-an-id/attendance', teacher)).toBeNull();
    expect(routeForPath(`/classes/${classId}/attendance/../x`, teacher)).toBeNull();
  });
  it('opens the child\'s attendance for a guardian (ATTENDANCE_MARKED)', () => {
    const studentId = '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    expect(routeForPath('/children', HOME, { studentId, date: '2026-11-25' })).toBe(`/child/${studentId}/attendance`);
    // The same notification inside a raw APNs body.
    expect(routeForPath('/children', HOME, { body: { studentId, date: '2026-11-25' } })).toBe(`/child/${studentId}/attendance`);
    // "/children" without a date (a link was decided) still opens the family list; a bad id is not followed.
    expect(routeForPath('/children', HOME, { studentId })).toBe(HOME);
    expect(routeForPath('/children', HOME, { studentId: '../../x', date: '2026-11-25' })).toBe(HOME);
    expect(routeForPath('/children', '/(teacher)/classes', { studentId, date: '2026-11-25' })).toBe('/(teacher)/classes');
  });
  it('ignores query, hash and a trailing slash', () => {
    expect(routeForPath('/notifications?x=1#top', HOME)).toBe('/notifications');
    expect(routeForPath('/notifications/', HOME)).toBe('/notifications');
  });
  it('stays put for unknown or unsafe paths', () => {
    for (const path of ['/messages/123', '//evil.example', 'https://evil.example', 'javascript:alert(1)', '/a\\b', '', '/\tx', null, 5, undefined]) {
      expect(routeForPath(path, HOME)).toBeNull();
    }
  });
  it('has nowhere to go "home" without a role', () => {
    expect(routeForPath('/', null)).toBeNull();
  });
});

describe('notification data', () => {
  it('reads path and id from plain data', () => {
    expect(pathFromNotificationData({ path: '/notifications', notificationId: 'n1' })).toBe('/notifications');
    expect(notificationIdFromData({ notificationId: 'n1' })).toBe('n1');
  });
  it('reads them from a raw APNs body, object or JSON text', () => {
    expect(pathFromNotificationData({ body: { path: '/settings' } })).toBe('/settings');
    expect(pathFromNotificationData({ body: '{"path":"/settings","notificationId":"n2"}' })).toBe('/settings');
    expect(notificationIdFromData({ body: '{"notificationId":"n2"}' })).toBe('n2');
  });
  it('tolerates junk', () => {
    expect(pathFromNotificationData(null)).toBeNull();
    expect(pathFromNotificationData('nope')).toBeNull();
    expect(pathFromNotificationData([1])).toBeNull();
    expect(pathFromNotificationData({ path: 3 })).toBeNull();
  });
});
