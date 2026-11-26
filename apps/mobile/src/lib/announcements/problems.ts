import { ApiRequestError } from '@/lib/api-error';

/** The stable `code` of an API problem (`announcement.no_recipients`...), or undefined (offline, timeout, no body). */
export function codeOf(error: unknown): string | undefined {
  return error instanceof ApiRequestError ? (error.problem?.code as string | undefined) : undefined;
}

export type SendProblemKind = 'no_recipients' | 'rate_limited' | 'offline' | 'withdrawn' | 'forbidden' | 'other';

/**
 * How the composer shows a failed publish or schedule. The message is always the API's own localized `problem.detail`
 * (`ProblemBanner`); the kind only decides the emphasis and which button stays: `no_recipients` means "change the audience",
 * `rate_limited` "wait", `offline` "retry" (publishing needs a connection: there is no queue).
 */
export function sendProblemKind(error: unknown): SendProblemKind {
  if (!(error instanceof ApiRequestError)) return 'other';
  if (error.status === undefined) return 'offline';
  const code = codeOf(error);
  if (code === 'announcement.no_recipients' || error.status === 422) return 'no_recipients';
  if (code === 'announcement.rate_limited' || error.status === 429) return 'rate_limited';
  if (code === 'announcement.withdrawn') return 'withdrawn';
  if (error.status === 403) return 'forbidden';
  return 'other';
}

export type RemindProblemKind = 'too_soon' | 'limit_reached' | 'nobody' | 'not_required' | 'rate_limited' | 'offline' | 'other';

/** The reminder's refusals: each already carries a localized `problem.detail`; `too_soon` also carries `Retry-After`. */
export function remindProblemKind(error: unknown): RemindProblemKind {
  if (!(error instanceof ApiRequestError)) return 'other';
  if (error.status === undefined) return 'offline';
  switch (codeOf(error)) {
    case 'announcement.reminder_too_soon':
      return 'too_soon';
    case 'announcement.reminder_limit_reached':
      return 'limit_reached';
    case 'announcement.nobody_to_remind':
      return 'nobody';
    case 'announcement.ack_not_required':
      return 'not_required';
    default:
      return error.status === 429 ? 'rate_limited' : 'other';
  }
}

/** Whole hours (rounded up, at least 1) to show for a `Retry-After` in seconds; undefined when the server sent none. */
export function retryAfterHours(seconds: number | undefined): number | undefined {
  return seconds === undefined ? undefined : Math.max(1, Math.ceil(seconds / 3600));
}

/** An announcement that is no longer there for this person (404) or is closed to them (403). */
export function isGone(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 404 || error.status === 403);
}
