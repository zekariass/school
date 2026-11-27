import type { ReportReasonCode } from './types';

/**
 * The reasons the report sheet offers, in this order. The codes are the API's own (`ModerationReportRequest.reasonCode`; there is
 * no endpoint that lists them, so the generated type is the contract and `reasons.test.ts` compares this list with `openapi.json`).
 * COPYRIGHT and INACCURATE belong to library and tutor content, which have no screen on the phone, so they are not offered.
 * The API decides what each reason leads to (SAFETY_CONCERN and HARASSMENT start a case at HIGH priority): this list only names them.
 */
export const REPORT_REASONS = ['HARASSMENT', 'INAPPROPRIATE', 'SAFETY_CONCERN', 'PRIVACY', 'SPAM', 'OTHER'] as const satisfies readonly ReportReasonCode[];

export type OfferedReason = (typeof REPORT_REASONS)[number];

/** Catalog keys by reason code (plural-free texts). */
export const REASON_LABEL = {
  HARASSMENT: 'mobile.moderation.reason.harassment',
  INAPPROPRIATE: 'mobile.moderation.reason.inappropriate',
  SAFETY_CONCERN: 'mobile.moderation.reason.safetyConcern',
  PRIVACY: 'mobile.moderation.reason.privacy',
  SPAM: 'mobile.moderation.reason.spam',
  OTHER: 'mobile.moderation.reason.other',
} as const satisfies Record<OfferedReason, string>;

/** The intro line of the sheet by what is reported. */
export const INTRO_LABEL = {
  POST: 'mobile.moderation.intro.post',
  POST_COMMENT: 'mobile.moderation.intro.comment',
  STORY: 'mobile.moderation.intro.story',
  MESSAGE: 'mobile.moderation.intro.message',
  USER: 'mobile.moderation.intro.person',
} as const;

export function isOfferedReason(value: unknown): value is OfferedReason {
  return typeof value === 'string' && (REPORT_REASONS as readonly string[]).includes(value);
}
