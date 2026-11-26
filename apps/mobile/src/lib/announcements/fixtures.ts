import type { AnnouncementCapabilities, AnnouncementView } from './types';

/** Test data shaped like the API's payloads (`AnnouncementView`, `AnnouncementCapabilities`). */

export function view(overrides: Partial<AnnouncementView> = {}): AnnouncementView {
  return {
    id: 'a1',
    schoolId: 's1',
    schoolName: 'Sunrise School',
    authorId: 'u1',
    authorName: 'Ms Abebech',
    title: 'Field trip',
    body: 'We go on Friday.',
    bodyFormat: 'PLAIN_TEXT',
    bodyLanguageCode: 'en',
    type: 'IMPORTANT',
    status: 'PUBLISHED',
    revision: 1,
    requiresAcknowledgement: true,
    translations: {},
    createdAt: '2026-11-26T08:00:00Z',
    publishedAt: '2026-11-26T08:01:00Z',
    can: { delete: false, edit: false, publish: false, remind: false, schedule: false, viewReport: false, withdraw: false },
    mine: { acknowledged: false, read: false, recipient: true, reacknowledgementNeeded: false },
    ...overrides,
  };
}

export function caps(overrides: Partial<AnnouncementCapabilities> = {}): AnnouncementCapabilities {
  return {
    canCompose: true,
    schoolWide: false,
    urgentAllowed: false,
    studentsEnabled: false,
    emailAvailable: false,
    smsAvailable: false,
    smsTypes: [],
    classes: [
      { id: 'c1', name: 'Grade 3A' },
      { id: 'c2', name: 'Grade 4B' },
    ],
    reminderMinHours: 24,
    timeZone: 'Africa/Addis_Ababa',
    smsMaxPerAnnouncement: 500,
    smsMaxPerDay: 1000,
    ...overrides,
  };
}
