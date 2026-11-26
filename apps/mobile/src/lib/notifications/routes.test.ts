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
  describe('the feed', () => {
    const postId = '2c1d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const schoolId = '3a9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const classId = '4b8d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    it('opens a post for everybody, with the school when the notification names it', () => {
      expect(routeForPath(`/feed/posts/${postId}`, HOME)).toBe(`/feed/post/${postId}`);
      expect(routeForPath(`/feed/posts/${postId}`, '/(teacher)/classes', { schoolId })).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
      expect(routeForPath(`/feed/posts/${postId}`, '/(student)/home', { body: { schoolId } })).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
    });
    it('opens a story', () => {
      const storyId = '5c7d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
      expect(routeForPath(`/feed/stories/${storyId}`, HOME)).toBe(`/feed/story/${storyId}`);
    });
    it('opens the approval queue for a teacher, and the home of anybody else', () => {
      expect(routeForPath('/feed/approvals', '/(teacher)/classes', { classId })).toBe(`/feed/approvals?classId=${classId}`);
      expect(routeForPath('/feed/approvals', '/(teacher)/classes', { classId, schoolId })).toBe(`/feed/approvals?schoolId=${schoolId}&classId=${classId}`);
      expect(routeForPath('/feed/approvals', HOME)).toBe(HOME);
    });
    it('never follows a bad id or a missing role', () => {
      expect(routeForPath('/feed/posts/not-an-id', HOME)).toBeNull();
      expect(routeForPath(`/feed/posts/${postId}/../x`, HOME)).toBeNull();
      expect(routeForPath(`/feed/posts/${postId}`, null)).toBeNull();
      expect(routeForPath(`/feed/posts/${postId}`, HOME, { schoolId: '../../x' })).toBe(`/feed/post/${postId}`);
    });
  });
  describe('the school id of a push (M8c follow-up)', () => {
    const postId = '2c1d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const storyId = '5c7d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const schoolId = '3a9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    // The push data of the backend: `path`, the ids of the thing, and `schoolId` for every notification that has a school.
    const push = { path: `/feed/posts/${postId}`, postId, schoolId, notificationId: 'n1' };
    it('opens a post, a story and the queue in the school the push names', () => {
      expect(routeForPath(push.path, HOME, push)).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
      expect(routeForPath(`/feed/stories/${storyId}`, HOME, { ...push, storyId })).toBe(`/feed/story/${storyId}?schoolId=${schoolId}`);
      expect(routeForPath('/feed/approvals', '/(teacher)/classes', { schoolId })).toBe(`/feed/approvals?schoolId=${schoolId}`);
    });
    it('reads the school from a raw APNs body too, object or text', () => {
      expect(routeForPath(push.path, HOME, { body: push })).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
      expect(routeForPath(push.path, HOME, { body: JSON.stringify(push) })).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
    });
    it('without a school (an older push) the route has none and the screen looks the post up', () => {
      expect(routeForPath(push.path, HOME, { path: push.path })).toBe(`/feed/post/${postId}`);
      expect(routeForPath(push.path, HOME, undefined)).toBe(`/feed/post/${postId}`);
    });
    it('the inbox row passes the school of the row', () => {
      expect(routeForPath(push.path, HOME, { path: push.path, schoolId })).toBe(`/feed/post/${postId}?schoolId=${schoolId}`);
    });
  });
  describe('announcements', () => {
    const announcementId = '6d1d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const schoolId = '3a9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
    const path = `/announcements/${announcementId}`;
    it('opens the announcement for every role, in the school the push names', () => {
      for (const home of [HOME, '/(teacher)/classes', '/(student)/home']) {
        expect(routeForPath(path, home, { path, announcementId, schoolId })).toBe(`/announcements/${announcementId}?schoolId=${schoolId}`);
      }
    });
    it('opens it without a school for an older push (the screen asks the caller\'s schools)', () => {
      expect(routeForPath(path, HOME, { path, announcementId })).toBe(`/announcements/${announcementId}`);
      expect(routeForPath(path, HOME)).toBe(`/announcements/${announcementId}`);
    });
    it('reads the school from a raw APNs body', () => {
      expect(routeForPath(path, HOME, { body: { path, schoolId } })).toBe(`/announcements/${announcementId}?schoolId=${schoolId}`);
    });
    it('opens the inbox for the bare path', () => {
      expect(routeForPath('/announcements', HOME)).toBe('/announcements');
    });
    it('never follows a bad id, a path with extra parts or a missing role; a bad school id is dropped', () => {
      expect(routeForPath('/announcements/not-an-id', HOME)).toBeNull();
      expect(routeForPath(`${path}/report`, HOME)).toBeNull();
      expect(routeForPath(`${path}/../x`, HOME)).toBeNull();
      expect(routeForPath(path, null)).toBeNull();
      expect(routeForPath(path, HOME, { schoolId: '../../x' })).toBe(`/announcements/${announcementId}`);
    });
  });
  it('ignores query, hash and a trailing slash', () => {
    expect(routeForPath('/notifications?x=1#top', HOME)).toBe('/notifications');
    expect(routeForPath('/notifications/', HOME)).toBe('/notifications');
  });
  it('stays put for unknown or unsafe paths', () => {
    for (const path of ['/messages/123', '/messages/../x', '/messages/groups/x', '//evil.example', 'https://evil.example', 'javascript:alert(1)', '/a\\b', '', '/\tx', null, 5, undefined]) {
      expect(routeForPath(path, HOME)).toBeNull();
    }
  });
  it('has nowhere to go "home" without a role', () => {
    expect(routeForPath('/', null)).toBeNull();
  });
});

describe('the messaging', () => {
  const conversationId = '6d7d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
  const schoolId = '3a9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed';
  it('opens the conversation for every role (NEW_MESSAGE, NEW_MESSAGE_QUIET, MESSAGE_OBSERVER_ADDED, MESSAGE_REPORTED)', () => {
    for (const home of [HOME, '/(teacher)/classes', '/(student)/home']) {
      expect(routeForPath(`/messages/${conversationId}`, home, { schoolId })).toBe(`/messages/${conversationId}`);
    }
  });
  it('puts nothing but the conversation id in the route (no text, no school)', () => {
    expect(routeForPath(`/messages/${conversationId}?text=hi#x`, HOME, { schoolId, body: 'secret' })).toBe(`/messages/${conversationId}`);
  });
  it('opens the messages tab of the role', () => {
    expect(routeForPath('/messages', HOME)).toBe('/(guardian)/messages');
    expect(routeForPath('/messages', '/(teacher)/classes')).toBe('/(teacher)/messages');
    expect(routeForPath('/messages', '/(student)/home')).toBe('/(student)/messages');
  });
  it('opens the group list for a teacher (GROUP_APPROVAL_REQUESTED), the home of anybody else', () => {
    expect(routeForPath('/messages/groups', '/(teacher)/classes', { schoolId })).toBe(`/messages/groups?schoolId=${schoolId}`);
    expect(routeForPath('/messages/groups', '/(teacher)/classes')).toBe('/messages/groups');
    expect(routeForPath('/messages/groups', HOME)).toBe(HOME);
  });
  it('needs a role and a real id', () => {
    expect(routeForPath(`/messages/${conversationId}`, null)).toBeNull();
    expect(routeForPath('/messages/not-an-id', HOME)).toBeNull();
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
