import type { components } from '@school/api-client';

type S = components['schemas'];

export type AnnouncementView = S['AnnouncementView'];
export type AnnouncementCard = S['AnnouncementCard'];
export type AnnouncementCapabilities = S['AnnouncementCapabilities'];
export type AnnouncementRequest = S['AnnouncementRequest'];
export type AnnouncementUpdateRequest = S['AnnouncementUpdateRequest'];
export type AnnouncementAudienceInput = S['AnnouncementAudienceInput'];
export type AnnouncementAudienceView = S['AnnouncementAudienceView'];
export type AnnouncementPreview = S['AnnouncementPreview'];
export type AnnouncementReportSummary = S['AnnouncementReportSummary'];
export type AnnouncementRecipientView = S['AnnouncementRecipientView'];
export type AnnouncementUnreadSummary = S['AnnouncementUnreadSummary'];
export type AnnouncementReminderView = S['AnnouncementReminderView'];
export type CommunicationChannels = S['CommunicationChannelsView'];
export type AnnouncementPage = S['CursorPageAnnouncementCard'];
export type RecipientPage = S['CursorPageAnnouncementRecipientView'];

export type AnnouncementType = AnnouncementView['type'];
export type AnnouncementStatus = AnnouncementView['status'];
export type AudienceRole = 'GUARDIAN' | 'STUDENT' | 'STAFF';
export type AudienceType = AnnouncementAudienceInput['type'];
export type Channel = NonNullable<AnnouncementRequest['channels']>[number];
export type PublishMode = NonNullable<AnnouncementRequest['mode']>;
export type RecipientFilter = 'UNREAD' | 'UNACKNOWLEDGED' | 'ACKNOWLEDGED' | 'FAILED';

/** Types in the order the picker shows them. */
export const ANNOUNCEMENT_TYPES: readonly AnnouncementType[] = ['NORMAL', 'IMPORTANT', 'URGENT', 'SAFETY'];

/** An urgent or safety announcement: an icon and a label always mark it, and it ignores quiet hours and mute. */
export function isUrgentType(type: AnnouncementType): boolean {
  return type === 'URGENT' || type === 'SAFETY';
}
