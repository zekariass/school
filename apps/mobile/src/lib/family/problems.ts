import { ApiRequestError } from '@/lib/api-error';

import { classifyInvitationFailure, type InvitationProblem } from '@school/validation/guardians';

/** The problem of a failed invitation or consent call, for choosing the message and the next step. */
export function invitationProblemOf(error: unknown): InvitationProblem {
  if (!(error instanceof ApiRequestError)) return { kind: 'network' };
  return classifyInvitationFailure(error.status, error.problem?.code, error.retryAfterSeconds ?? null);
}

/** What to tell the adult, as an i18n key with its values, or `null` to show the server's own (localized) detail. */
export type InvitationMessage =
  | { key: 'invite.contactUnavailable' }
  | { key: 'invite.rateLimited'; values: { seconds: number } }
  | { key: 'invite.loadFailed' }
  | { key: 'invite.failed' };

export function invitationMessageFor(error: unknown): { message: InvitationMessage; detail: string | undefined } {
  const problem = invitationProblemOf(error);
  const detail = error instanceof ApiRequestError ? error.problem?.detail : undefined;
  switch (problem.kind) {
    case 'contactUnavailable':
      return { message: { key: 'invite.contactUnavailable' }, detail: undefined };
    case 'rateLimited':
      return { message: { key: 'invite.rateLimited', values: { seconds: problem.retryAfterSeconds ?? 60 } }, detail: undefined };
    case 'network':
      return { message: { key: 'invite.loadFailed' }, detail: undefined };
    default:
      return { message: { key: 'invite.failed' }, detail };
  }
}

/** Seconds to wait when the failure is a rate limit. */
export function retryAfterOfInvitation(error: unknown): number | undefined {
  const problem = invitationProblemOf(error);
  return problem.kind === 'rateLimited' ? (problem.retryAfterSeconds ?? 60) : undefined;
}
