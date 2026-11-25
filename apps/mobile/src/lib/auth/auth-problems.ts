import { ApiRequestError } from '@/lib/api-error';

import type { SessionEndReason } from './session-events';

/** A message to show: an i18n key plus its ICU values. */
export interface AuthMessage {
  key:
    | 'mobile.verify.errors.invalidCode'
    | 'mobile.verify.errors.rateLimited'
    | 'mobile.verify.errors.inactive'
    | 'mobile.verify.errors.network'
    | 'mobile.verify.errors.generic';
  values?: { seconds: number };
}

/**
 * Maps a failed sign-in call to a localized message of our own. Known codes get app copy
 * (so the text follows the app language even before the server's `Accept-Language` is
 * honoured); anything else is `generic` and the caller may show the server's problem.
 */
export function authMessageFor(error: unknown): AuthMessage {
  if (!(error instanceof ApiRequestError)) return { key: 'mobile.verify.errors.generic' };
  if (error.status === undefined) return { key: 'mobile.verify.errors.network' };

  switch (error.problem?.code) {
    case 'auth.invalid_code':
      return { key: 'mobile.verify.errors.invalidCode' };
    case 'auth.rate_limited':
      return { key: 'mobile.verify.errors.rateLimited', values: { seconds: error.retryAfterSeconds ?? 60 } };
    case 'auth.account_inactive':
      return { key: 'mobile.verify.errors.inactive' };
  }
  if (error.status === 429) {
    return { key: 'mobile.verify.errors.rateLimited', values: { seconds: error.retryAfterSeconds ?? 60 } };
  }
  return error.status >= 500 ? { key: 'mobile.verify.errors.network' } : { key: 'mobile.verify.errors.generic' };
}

/** Seconds the user must wait before trying again, when the failure is a rate limit. */
export function retryAfterOf(error: unknown): number | undefined {
  const message = authMessageFor(error);
  return message.key === 'mobile.verify.errors.rateLimited' ? message.values?.seconds : undefined;
}

/** i18n key of the banner shown on the sign-in screen after the session was ended for us. */
export function endNoticeKey(reason: SessionEndReason) {
  return {
    expired: 'mobile.session.notice.expired',
    revoked: 'mobile.session.notice.revoked',
    inactive: 'mobile.session.notice.inactive',
  }[reason] as `mobile.session.notice.${SessionEndReason}`;
}

/** A message of the student code screen. */
export interface StudentCodeMessage {
  key:
    | 'mobile.studentCode.errors.invalid'
    | 'mobile.studentCode.errors.rateLimited'
    | 'mobile.studentCode.errors.inactive'
    | 'mobile.studentCode.errors.network'
    | 'mobile.studentCode.errors.generic';
  values?: { seconds: number };
}

/**
 * Child-friendly copy for a failed class-code sign-in. Every wrong, expired or revoked code is the
 * same answer from the server (400 `auth.invalid_code`), so the app never says which it was.
 */
export function studentCodeMessageFor(error: unknown): StudentCodeMessage {
  if (!(error instanceof ApiRequestError)) return { key: 'mobile.studentCode.errors.generic' };
  if (error.status === undefined) return { key: 'mobile.studentCode.errors.network' };
  if (error.problem?.code === 'auth.invalid_code') return { key: 'mobile.studentCode.errors.invalid' };
  if (error.problem?.code === 'auth.account_inactive') return { key: 'mobile.studentCode.errors.inactive' };
  if (error.status === 429 || error.problem?.code === 'auth.rate_limited') {
    return { key: 'mobile.studentCode.errors.rateLimited', values: { seconds: error.retryAfterSeconds ?? 60 } };
  }
  return error.status >= 500 ? { key: 'mobile.studentCode.errors.network' } : { key: 'mobile.studentCode.errors.generic' };
}

/** Seconds to wait before another try, when the failure was the rate limit. */
export function studentCodeRetryAfter(error: unknown): number | undefined {
  const message = studentCodeMessageFor(error);
  return message.key === 'mobile.studentCode.errors.rateLimited' ? message.values?.seconds : undefined;
}
