import { ApiRequestError, problemOf } from '@/lib/api-error';

/**
 * How a failed report is explained. The API's own localized `problem.detail` is shown whenever it sent one, except for 404: a
 * target the reporter cannot see (a restricted guardian, a deleted post, somebody else's conversation) must look exactly like a
 * target that does not exist, so a 404 always gets the same generic "not found" text and never a reason. Everything the screen
 * does with a failure is decided here, so it is the same for every kind of target.
 */
export type ReportFailure =
  | { kind: 'notFound' }
  | { kind: 'rateLimited'; detail: string | undefined; retryAfterSeconds: number | undefined }
  | { kind: 'refused'; detail: string | undefined }
  | { kind: 'offline' }
  | { kind: 'failed'; detail: string | undefined };

export function classifyReportFailure(error: unknown): ReportFailure {
  if (!(error instanceof ApiRequestError) || error.status === undefined) return { kind: 'offline' };
  const detail = problemOf(error)?.detail || undefined;
  if (error.status === 404) return { kind: 'notFound' };
  if (error.status === 429) return { kind: 'rateLimited', detail, retryAfterSeconds: error.retryAfterSeconds };
  // 400 (bad details), 403 (reporting is off for the school: only said once the target is visible), 409 (your own content)
  if (error.status >= 400 && error.status < 500) return { kind: 'refused', detail };
  return { kind: 'failed', detail };
}

/** Catalog keys by unit (plural-free: the number is a placeholder, the unit is its own text). */
export const RETRY_LABEL = {
  seconds: 'mobile.moderation.retry.seconds',
  minutes: 'mobile.moderation.retry.minutes',
  hours: 'mobile.moderation.retry.hours',
} as const;

/** `Retry-After` as one number and a unit, for a plural-free message (see `RETRY_LABEL`). */
export function retryAfterParts(seconds: number): { unit: 'seconds' | 'minutes' | 'hours'; value: number } {
  const whole = Math.max(1, Math.ceil(seconds));
  if (whole < 90) return { unit: 'seconds', value: whole };
  if (whole < 90 * 60) return { unit: 'minutes', value: Math.ceil(whole / 60) };
  return { unit: 'hours', value: Math.ceil(whole / 3600) };
}
