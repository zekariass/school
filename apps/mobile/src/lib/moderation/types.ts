import type { components } from '@school/api-client';

type S = components['schemas'];

/** What the API accepts (`POST /moderation/reports`). */
export type ReportRequest = S['ModerationReportRequest'];
export type ReportReasonCode = ReportRequest['reasonCode'];
export type ApiTargetType = ReportRequest['targetType'];
export type ReportReceipt = S['ModerationReportReceipt'];
export type EmergencyInfo = S['SafetyEmergencyInfo'];

/**
 * The target types the phone offers a Report action for. A portfolio item is not here on purpose: no module registers a
 * `ReportTarget` for it yet (U-27), so the API would answer 400 `moderation.target_type_unsupported`.
 */
export type ReportableType = Extract<ApiTargetType, 'POST' | 'POST_COMMENT' | 'STORY' | 'MESSAGE' | 'USER'>;

/**
 * What is being reported: ids only (never the content, never a name). `schoolId` is used to pick the country of the safety text;
 * a message in a conversation may not carry one.
 */
export interface ReportTarget {
  type: ReportableType;
  id: string;
  schoolId?: string | null | undefined;
}
