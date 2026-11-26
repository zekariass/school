import type { components } from '@school/api-client';

import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';

import { PAGE_SIZE, type InboxFilter, type InboxSort } from './keys';
import { inboxQueryParams } from './inbox';
import type {
  AnnouncementCapabilities,
  AnnouncementPage,
  AnnouncementPreview,
  AnnouncementReminderView,
  AnnouncementReportSummary,
  AnnouncementRequest,
  AnnouncementStatus,
  AnnouncementUnreadSummary,
  AnnouncementUpdateRequest,
  AnnouncementView,
  CommunicationChannels,
  RecipientFilter,
  RecipientPage,
} from './types';

type S = components['schemas'];

/**
 * One call per endpoint, typed from the generated client. Only `createAnnouncement` carries an `Idempotency-Key`; publishing,
 * acknowledging, reading, withdrawing and the rest are idempotent by themselves. Nothing here is persisted.
 */

const page = (cursor: string | undefined, limit: number = PAGE_SIZE) => ({ limit, ...(cursor ? { cursor } : {}) });
const path = (schoolId: string, announcementId: string) => ({ params: { path: { schoolId, announcementId } } });

// --- reading (the recipient's side) ----------------------------------------------------------------

export const fetchInbox = (schoolId: string | null, filter: InboxFilter, sort: InboxSort, cursor?: string): Promise<AnnouncementPage> =>
  request(async () => unwrap(await api.GET('/api/v1/me/announcements', { params: { query: { ...inboxQueryParams({ schoolId, filter, sort }), ...page(cursor) } } })));

export const fetchSummary = (): Promise<AnnouncementUnreadSummary> =>
  request(async () => unwrap(await api.GET('/api/v1/me/announcements/summary')));

export const fetchAnnouncement = (schoolId: string, announcementId: string): Promise<AnnouncementView> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/announcements/{announcementId}', path(schoolId, announcementId))));

export const markRead = (schoolId: string, announcementId: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/read', path(schoolId, announcementId))));

export const acknowledge = (schoolId: string, announcementId: string): Promise<AnnouncementView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/acknowledge', path(schoolId, announcementId))));

// --- the author's side -------------------------------------------------------------------------------

export const fetchCapabilities = (schoolId: string): Promise<AnnouncementCapabilities> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/announcements/capabilities', { params: { path: { schoolId } } })));

export const fetchAuthored = (schoolId: string, status: AnnouncementStatus | null, mine: boolean, cursor?: string): Promise<AnnouncementPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/announcements/authored', {
        params: { path: { schoolId }, query: { ...page(cursor), ...(status ? { status } : {}), mine } },
      }),
    ),
  );

export const createAnnouncement = (schoolId: string, body: AnnouncementRequest, idempotencyKey: string): Promise<AnnouncementView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements', { params: { path: { schoolId } }, body, headers: { 'Idempotency-Key': idempotencyKey } })),
  );

export const updateAnnouncement = (schoolId: string, announcementId: string, body: AnnouncementUpdateRequest): Promise<AnnouncementView> =>
  request(async () => unwrap(await api.PATCH('/api/v1/schools/{schoolId}/announcements/{announcementId}', { ...path(schoolId, announcementId), body })));

export const deleteDraft = (schoolId: string, announcementId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/schools/{schoolId}/announcements/{announcementId}', path(schoolId, announcementId))));

export const previewAudience = (schoolId: string, announcementId: string): Promise<AnnouncementPreview> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/announcements/{announcementId}/preview', path(schoolId, announcementId))));

export const publish = (schoolId: string, announcementId: string): Promise<AnnouncementView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/publish', path(schoolId, announcementId))));

export const schedule = (schoolId: string, announcementId: string, scheduledFor: string): Promise<AnnouncementView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/schedule', { ...path(schoolId, announcementId), body: { scheduledFor } })),
  );

export const unschedule = (schoolId: string, announcementId: string): Promise<AnnouncementView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/unschedule', path(schoolId, announcementId))));

export const withdraw = (schoolId: string, announcementId: string, reason: string | null): Promise<AnnouncementView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/withdraw', {
        ...path(schoolId, announcementId),
        body: reason ? { reason } : {},
      }),
    ),
  );

// --- the report --------------------------------------------------------------------------------------

export const fetchReport = (schoolId: string, announcementId: string): Promise<AnnouncementReportSummary> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/announcements/{announcementId}/report', path(schoolId, announcementId))));

export const fetchRecipients = (schoolId: string, announcementId: string, filter: RecipientFilter, cursor?: string): Promise<RecipientPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/announcements/{announcementId}/report/recipients', {
        params: { path: { schoolId, announcementId }, query: { filter, ...page(cursor, 30) } },
      }),
    ),
  );

export const remind = (schoolId: string, announcementId: string): Promise<AnnouncementReminderView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/announcements/{announcementId}/remind', path(schoolId, announcementId))));

// --- the person's own channel choices ---------------------------------------------------------------

export const fetchChannels = (): Promise<CommunicationChannels> =>
  request(async () => unwrap(await api.GET('/api/v1/me/communication-channels')));

export const putChannels = (body: S['CommunicationChannelsRequest']): Promise<CommunicationChannels> =>
  request(async () => unwrap(await api.PUT('/api/v1/me/communication-channels', { body })));

// --- audience choices for managers -------------------------------------------------------------------

export const fetchGradeLevels = (schoolId: string): Promise<S['GradeLevelView'][]> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/grade-levels', { params: { path: { schoolId } } })));

export const fetchClasses = (schoolId: string, q: string): Promise<S['CursorPageClassView']> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/classes', {
        params: { path: { schoolId }, query: { status: 'ACTIVE', limit: 50, ...(q ? { q } : {}) } },
      }),
    ),
  );
