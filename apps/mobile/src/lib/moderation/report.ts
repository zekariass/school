import { isOfferedReason, type OfferedReason } from './reasons';
import type { ReportRequest, ReportTarget } from './types';

/** The most the API takes (`moderation.details_too_long`); counted like the server does (UTF-16 units of the cleaned text). */
export const MAX_DETAILS = 1000;

export type ReportInvalid = 'reasonRequired' | 'detailsTooLong';

export interface ReportDraft {
  reason: OfferedReason | null;
  details: string;
}

/** Plain text: no control characters (line breaks and tabs stay), no surrounding blanks, like the server's cleaning. */
export function cleanDetails(raw: string): string {
  return raw
    .replace(/\r\n?/g, '\n')
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    .trim();
}

/** The first thing wrong with the draft, or null. */
export function validateReport(draft: ReportDraft): ReportInvalid | null {
  if (!isOfferedReason(draft.reason)) return 'reasonRequired';
  if (cleanDetails(draft.details).length > MAX_DETAILS) return 'detailsTooLong';
  return null;
}

/**
 * The request body of `POST /moderation/reports`: the target as ids, the reason, and the details only when there are some.
 * Returns null when the draft is not valid (the sheet then shows the message of `validateReport`).
 */
export function buildReportRequest(target: ReportTarget, draft: ReportDraft): ReportRequest | null {
  if (validateReport(draft) !== null || draft.reason === null) return null;
  const details = cleanDetails(draft.details);
  return {
    targetType: target.type,
    targetId: target.id,
    reasonCode: draft.reason,
    ...(details ? { details } : {}),
  };
}

/**
 * One `Idempotency-Key` per user action: a retry of the same report (the connection dropped, the answer was lost) reuses its key
 * so the server cannot file it twice; any change to the target, reason or details is a new action with a new key.
 */
export interface ReportAttempt {
  key: string;
  fingerprint: string;
}

export function attemptFor(previous: ReportAttempt | null, body: ReportRequest, newKey: () => string): ReportAttempt {
  const fingerprint = JSON.stringify([body.targetType, body.targetId, body.reasonCode, body.details ?? '']);
  return previous && previous.fingerprint === fingerprint ? previous : { key: newKey(), fingerprint };
}

/** Whether to keep the attempt after a failure: only when the server may have received it (no answer at all, or a 5xx). */
export function keepAttemptAfter(status: number | undefined): boolean {
  return status === undefined || status >= 500;
}
