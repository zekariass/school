import { describe, expect, it } from 'vitest';

import { view } from './fixtures';
import { ackWithCard, readWithCard } from './cards';
import { isAnnouncementNotification, isUrgentAnnouncementData, notificationIdsFor } from './notifications';
import { percent, recipientFilters } from './report';
import { summarizeAudiences, translationFor, wasEdited } from './summary';

describe('summarizeAudiences', () => {
  it('groups rows of one kind and roles', () => {
    expect(
      summarizeAudiences([
        { type: 'CLASS', classId: 'a', roles: ['GUARDIAN'] },
        { type: 'CLASS', classId: 'b', roles: ['GUARDIAN'] },
        { type: 'SCHOOL', roles: ['STAFF', 'GUARDIAN'] },
      ]),
    ).toEqual([
      { type: 'CLASS', count: 2, roles: ['GUARDIAN'] },
      { type: 'SCHOOL', count: 1, roles: ['GUARDIAN', 'STAFF'] },
    ]);
    expect(summarizeAudiences(undefined)).toEqual([]);
  });
});

describe('translationFor', () => {
  const v = view({ bodyLanguageCode: 'en', translations: { am: { title: 'ርዕስ', body: 'አካል' } } });
  it('offers a translation written for the reader\'s language', () => {
    expect(translationFor(v, 'am')).toEqual({ title: 'ርዕስ', body: 'አካል' });
  });
  it('offers nothing when the text is already in that language, when none exists or for a withdrawn tombstone (no body)', () => {
    expect(translationFor(v, 'en')).toBeNull();
    expect(translationFor(view(), 'am')).toBeNull();
    const { body: _body, ...tombstone } = v;
    expect(translationFor(tombstone as typeof v, 'am')).toBeNull();
  });
});

describe('wasEdited', () => {
  it('is true after a revision or an edit time', () => {
    expect(wasEdited({ revision: 1 })).toBe(false);
    expect(wasEdited({ revision: 2 })).toBe(true);
    expect(wasEdited({ revision: 1, editedAt: 'x' })).toBe(true);
  });
});

describe('cards after a read or an acknowledgement', () => {
  const card = { id: 'a', requiresAcknowledgement: true } as never;
  it('keeps the first read time', () => {
    expect(readWithCard(card, 'now')).toMatchObject({ readAt: 'now' });
    const read = { ...(card as object), readAt: 'first' } as never;
    expect(readWithCard(read, 'now')).toBe(read);
  });
  it('acknowledging also reads', () => {
    expect(ackWithCard(card, 'now')).toMatchObject({ readAt: 'now', acknowledgedAt: 'now' });
  });
});

describe('report helpers', () => {
  it('percent is bounded and safe without recipients', () => {
    expect(percent(1, 3)).toBe(33);
    expect(percent(5, 3)).toBe(100);
    expect(percent(1, 0)).toBe(0);
  });
  it('offers the unconfirmed first when a confirmation is asked, else the unread', () => {
    expect(recipientFilters(true)[0]).toBe('UNACKNOWLEDGED');
    expect(recipientFilters(false)).toEqual(['UNREAD', 'FAILED']);
  });
});

describe('isUrgentAnnouncementData', () => {
  it('knows urgent and safety pushes, also inside a raw APNs body', () => {
    expect(isUrgentAnnouncementData({ announcementType: 'URGENT' })).toBe(true);
    expect(isUrgentAnnouncementData({ announcementType: 'SAFETY' })).toBe(true);
    expect(isUrgentAnnouncementData({ body: { announcementType: 'SAFETY' } })).toBe(true);
  });
  it('is false for everything else', () => {
    for (const data of [{ announcementType: 'IMPORTANT' }, { announcementType: 'NORMAL' }, {}, null, undefined, 'URGENT', { priority: 'URGENT' }]) {
      expect(isUrgentAnnouncementData(data)).toBe(false);
    }
  });
});

describe('notificationIdsFor', () => {
  const items = [
    { id: 'n1', data: { announcementId: 'a1', path: '/announcements/a1' } },
    { id: 'n2', data: { announcementId: 'a1' }, readAt: '2026-11-26T10:00:00Z' },
    { id: 'n3', data: { announcementId: 'a2' } },
    { id: 'n4', data: { postId: 'p1' } },
    { id: 'n5' },
  ];
  it('takes the unread notifications of that announcement only', () => {
    expect(notificationIdsFor(items, 'a1')).toEqual(['n1']);
    expect(notificationIdsFor(items, 'a2')).toEqual(['n3']);
    expect(notificationIdsFor(items, 'a3')).toEqual([]);
  });
});

describe('isAnnouncementNotification', () => {
  it('recognises the six types and the paths', () => {
    for (const typeCode of ['ANNOUNCEMENT_PUBLISHED', 'ACK_REQUESTED', 'ANNOUNCEMENT_UPDATED', 'ANNOUNCEMENT_REMINDER', 'ANNOUNCEMENT_WITHDRAWN', 'ANNOUNCEMENT_PUBLISH_BLOCKED']) {
      expect(isAnnouncementNotification({ typeCode })).toBe(true);
    }
    expect(isAnnouncementNotification({ data: { path: '/announcements/abc' } })).toBe(true);
    expect(isAnnouncementNotification({ typeCode: 'NEW_POST', data: { path: '/feed/posts/abc' } })).toBe(false);
    expect(isAnnouncementNotification({})).toBe(false);
  });
});
