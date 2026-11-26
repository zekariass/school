import { describe, expect, it } from 'vitest';

import { routeForPath } from '@/lib/notifications/routes';

import { ackState, canAcknowledge } from './ack';
import { allowedKinds, allowedRoles, allowedTypes, defaultChoice } from './audience';
import { blockedReason, initialComposer, toDraftRequest } from './composer';
import { isUnreadCard, needsAcknowledgementCard, showNewBadge } from './inbox';
import { isAnnouncementNotification } from './notifications';
import { percent } from './report';
import { translationFor } from './summary';
import type { AnnouncementCapabilities, AnnouncementCard, AnnouncementReportSummary, AnnouncementView } from './types';
import { linkify } from './linkify';

/**
 * Payloads as the API (M10a, with `reacknowledgementNeeded` and `timeZone`) really sent them in the smoke test, run through the
 * app's own rules. If the backend changes a shape these fail first.
 */

const SCHOOL = '01a2177d-b0d2-7c20-8edc-7c56d48e9164';
const ANN = '01a2177e-3fb5-7e9e-9c0c-0cbd0e5a4a11';

const afterMaterialEdit = {
  id: ANN,
  schoolId: SCHOOL,
  schoolName: 'Sunrise School',
  authorId: '01a2177d-bd9f-7a14-8cab-770313acb16c',
  authorName: 'Abebech Teacher',
  type: 'IMPORTANT',
  status: 'PUBLISHED',
  title: 'Field trip on Friday (museum)',
  body: 'We visit the museum. NEW: leave at 07:30. See https://school.example/trip. <b>not bold</b>',
  bodyFormat: 'PLAIN_TEXT',
  bodyLanguageCode: 'en',
  translations: {},
  requiresAcknowledgement: true,
  publishedAt: '2026-11-26T10:54:40.517226Z',
  revision: 3,
  editedAt: '2026-11-26T10:54:45.100000Z',
  createdAt: '2026-11-26T10:54:36.938034Z',
  mine: {
    read: true,
    readAt: '2026-11-26T10:54:45.976852Z',
    acknowledged: true,
    acknowledgedAt: '2026-11-26T10:54:46.271859Z',
    acknowledgedRevision: 1,
    recipient: true,
    reacknowledgementNeeded: true,
  },
  can: { edit: false, publish: false, schedule: false, withdraw: false, delete: false, viewReport: false, remind: false },
} satisfies AnnouncementView;

const withdrawn = {
  id: '01a2177e-5a60-7d3b-8f51-1d1b2f41c001',
  schoolId: SCHOOL,
  schoolName: 'Sunrise School',
  authorId: '01a2177d-bd9f-7a14-8cab-770313acb16c',
  authorName: 'Abebech Teacher',
  type: 'IMPORTANT',
  status: 'WITHDRAWN',
  title: 'Wrong date',
  bodyFormat: 'PLAIN_TEXT',
  bodyLanguageCode: 'en',
  translations: {},
  requiresAcknowledgement: true,
  publishedAt: '2026-11-26T10:38:53.944218Z',
  revision: 1,
  withdrawnAt: '2026-11-26T10:38:55.259928Z',
  withdrawalReason: 'Wrong date, a new one follows.',
  createdAt: '2026-11-26T10:38:53.867238Z',
  mine: { read: false, acknowledged: false, recipient: true, reacknowledgementNeeded: false },
  can: { edit: false, publish: false, schedule: false, withdraw: false, delete: false, viewReport: false, remind: false },
} satisfies AnnouncementView;

const card = {
  id: ANN,
  schoolId: SCHOOL,
  schoolName: 'Sunrise School',
  authorId: '01a2177d-bd9f-7a14-8cab-770313acb16c',
  authorName: 'Abebech Teacher',
  type: 'IMPORTANT',
  status: 'PUBLISHED',
  title: 'Field trip on Friday',
  excerpt: 'We visit the museum. Bring lunch and https://school.example/trip for details. <b>not bold</b>',
  requiresAcknowledgement: true,
  publishedAt: '2026-11-26T10:54:40.517226Z',
  revision: 1,
  createdAt: '2026-11-26T10:54:36.938034Z',
} satisfies AnnouncementCard;

const teacherCaps = {
  canCompose: true,
  schoolWide: false,
  urgentAllowed: false,
  studentsEnabled: false,
  emailAvailable: true,
  smsAvailable: false,
  smsTypes: [],
  classes: [{ id: '01a2177d-936b-772a-af6d-6cbaf76cede0', name: 'Grade 3A' }],
  reminderMinHours: 24,
  timeZone: 'Africa/Addis_Ababa',
} satisfies AnnouncementCapabilities;

const managerCaps = { ...teacherCaps, schoolWide: true, urgentAllowed: true, classes: [] } satisfies AnnouncementCapabilities;

const report = {
  announcementId: ANN,
  status: 'PUBLISHED',
  recipients: 2,
  delivered: 2,
  failed: 0,
  read: 1,
  acknowledged: 1,
  requiresAcknowledgement: true,
  deliveryComplete: true,
  push: { sent: 0, failed: 0, skipped: 2, pending: 0 },
  email: { sent: 0, failed: 0, skipped: 0, pending: 0 },
  sms: { sent: 0, failed: 0, skipped: 0, pending: 0 },
  lateReaders: 0,
  reminders: 0,
} satisfies AnnouncementReportSummary;

/** `ACK_REQUESTED` of an URGENT announcement, as the inbox and the push carry it. */
const urgentNotification = {
  id: '01a2177e-8a11-7b33-b0c1-3e0f5d9ac111',
  typeCode: 'ACK_REQUESTED',
  schoolId: SCHOOL,
  title: 'Please acknowledge: Early closing today',
  data: { path: `/announcements/${ANN}`, schoolId: SCHOOL, announcementId: ANN, announcementType: 'URGENT' },
  priority: 'URGENT',
};

describe('the detail the API sends after a material edit', () => {
  it('asks the reader to confirm again, and the reader sees no author buttons', () => {
    expect(ackState(afterMaterialEdit)).toBe('renew');
    expect(canAcknowledge(afterMaterialEdit)).toBe(true);
    expect(showNewBadge(afterMaterialEdit)).toBe(false);
    expect(Object.values(afterMaterialEdit.can).some(Boolean)).toBe(false);
  });
  it('keeps markup as text and finds the one real link', () => {
    const segments = linkify(afterMaterialEdit.body);
    expect(segments.filter((s) => s.type === 'link').map((s) => s.type === 'link' && s.url)).toEqual(['https://school.example/trip']);
    expect(segments.map((s) => s.text).join('')).toBe(afterMaterialEdit.body);
    expect(translationFor(afterMaterialEdit, 'am')).toBeNull();
  });
});

describe('the withdrawn tombstone', () => {
  it('has no body, a public reason and nothing to confirm', () => {
    expect('body' in withdrawn).toBe(false);
    expect(ackState(withdrawn)).toBe('withdrawn');
    expect(showNewBadge(withdrawn)).toBe(false);
    expect(translationFor(withdrawn, 'am')).toBeNull();
  });
});

describe('an inbox card', () => {
  it('is unread and waiting for a confirmation until the person reads and confirms it', () => {
    expect(isUnreadCard(card)).toBe(true);
    expect(needsAcknowledgementCard(card)).toBe(true);
    expect(isUnreadCard({ ...card, readAt: '2026-11-26T11:00:00Z' })).toBe(false);
    expect(needsAcknowledgementCard({ ...card, acknowledgedAt: '2026-11-26T11:00:00Z' })).toBe(false);
  });
});

describe('the capabilities', () => {
  it('give a teacher the composer for the own class, in the school time zone', () => {
    expect(allowedKinds(teacherCaps)).toEqual(['CLASS']);
    expect(allowedRoles(teacherCaps)).toEqual(['GUARDIAN']);
    expect(allowedTypes(teacherCaps)).toEqual(['NORMAL', 'IMPORTANT']);
    const state = { ...initialComposer(teacherCaps), title: 'Hello', body: 'Body' };
    expect(blockedReason(state, teacherCaps)).toBeNull();
    expect(toDraftRequest(state, teacherCaps, 'en')).toMatchObject({
      mode: 'DRAFT',
      channels: ['IN_APP', 'PUSH'],
      audiences: [{ type: 'CLASS', classId: teacherCaps.classes[0]!.id, roles: ['GUARDIAN'] }],
    });
    expect(teacherCaps.timeZone).toBe('Africa/Addis_Ababa');
  });
  it('give a manager the whole school and the urgent types', () => {
    expect(allowedKinds(managerCaps)).toEqual(['SCHOOL', 'GRADE_LEVEL', 'CLASS']);
    expect(allowedTypes(managerCaps)).toContain('SAFETY');
    expect(defaultChoice(managerCaps).kind).toBe('SCHOOL');
  });
});

describe('the report', () => {
  it('turns into the meters of the screen', () => {
    expect(percent(report.read, report.recipients)).toBe(50);
    expect(percent(report.acknowledged, report.recipients)).toBe(50);
  });
});

describe('an urgent notification', () => {
  it('is recognised and opens the announcement in its school for every role', () => {
    expect(isAnnouncementNotification(urgentNotification)).toBe(true);
    for (const home of ['/(guardian)/home', '/(teacher)/classes', '/(student)/home']) {
      expect(routeForPath(urgentNotification.data.path, home, urgentNotification.data)).toBe(`/announcements/${ANN}?schoolId=${SCHOOL}`);
    }
  });
});
