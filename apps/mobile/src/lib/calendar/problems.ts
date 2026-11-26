import { ApiRequestError } from '@/lib/api-error';

/** The stable `code` of an API problem (`event.rsvp_closed`...), or undefined (offline, timeout, no body). */
export function codeOf(error: unknown): string | undefined {
  return error instanceof ApiRequestError ? (error.problem?.code as string | undefined) : undefined;
}

export type CalendarProblemKind =
  | 'offline'
  | 'featureDisabled'
  | 'gone'
  | 'forbidden'
  | 'conflict'
  | 'invalid'
  | 'rateLimited'
  | 'other';

/**
 * How a failed call is shown. The message itself is always the API's own localized `problem.detail` (`ProblemBanner` or an inline
 * line); the kind only decides the emphasis and whether the form stays (it always does: a failure never empties it).
 */
export function problemKind(error: unknown): CalendarProblemKind {
  if (!(error instanceof ApiRequestError)) return 'other';
  if (error.status === undefined) return 'offline';
  if (codeOf(error) === 'calendar.feature_disabled') return 'featureDisabled';
  if (error.status === 404) return 'gone';
  if (error.status === 403) return 'forbidden';
  if (error.status === 409) return 'conflict';
  if (error.status === 400 || error.status === 422) return 'invalid';
  if (error.status === 429) return 'rateLimited';
  return 'other';
}

/** An event that is no longer there for this person (404: deleted, or never theirs to see) or is closed to them. */
export function isGone(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 404;
}

/** The calendar of a school that has it switched off. */
export function isFeatureDisabled(error: unknown): boolean {
  return codeOf(error) === 'calendar.feature_disabled';
}

/**
 * Which part of the form a problem code belongs to, so the message can sit under that field. Codes without a field are shown at
 * the top of the form (the API's `detail` either way).
 */
export function formFieldOf(error: unknown): 'title' | 'description' | 'location' | 'time' | 'repeat' | 'rsvp' | 'audience' | null {
  const code = codeOf(error);
  if (!code) return null;
  if (code.startsWith('event.title')) return 'title';
  if (code.startsWith('event.description')) return 'description';
  if (code.startsWith('event.location')) return 'location';
  if (code.startsWith('event.time') || code === 'event.occurrence_shift_too_large') return 'time';
  if (code.startsWith('event.recurrence')) return 'repeat';
  if (code.startsWith('event.rsvp')) return 'rsvp';
  if (code.startsWith('event.scope') || code.startsWith('event.type') || code.startsWith('event.audience') || code === 'event.no_participants' || code === 'event.meeting_not_allowed') return 'audience';
  return null;
}
