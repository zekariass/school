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
