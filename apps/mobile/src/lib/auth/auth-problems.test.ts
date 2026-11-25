import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import { ApiRequestError } from '@/lib/api-error';

import { authMessageFor, endNoticeKey, retryAfterOf, studentCodeMessageFor, studentCodeRetryAfter } from './auth-problems';
import { endReasonFor, isTerminalSessionCode } from './session-events';

function problemError(status: number, code: string, retryAfterSeconds?: number) {
  return new ApiRequestError(
    status,
    { status, code, title: code, type: 'about:blank' },
    { retryAfterSeconds },
  );
}

function resolve(path: string): unknown {
  return path.split('.').reduce<unknown>((node, key) => (node as Record<string, unknown> | undefined)?.[key], messages.en);
}

describe('authMessageFor', () => {
  it('maps an invalid code', () => {
    expect(authMessageFor(problemError(400, 'auth.invalid_code'))).toEqual({ key: 'mobile.verify.errors.invalidCode' });
  });

  it('maps rate limiting with the Retry-After seconds', () => {
    expect(authMessageFor(problemError(429, 'auth.rate_limited', 42))).toEqual({
      key: 'mobile.verify.errors.rateLimited',
      values: { seconds: 42 },
    });
    expect(retryAfterOf(problemError(429, 'auth.rate_limited', 42))).toBe(42);
  });

  it('falls back to a minute when a 429 has no Retry-After', () => {
    expect(authMessageFor(new ApiRequestError(429))).toEqual({
      key: 'mobile.verify.errors.rateLimited',
      values: { seconds: 60 },
    });
  });

  it('maps inactive accounts, transport failures and server errors', () => {
    expect(authMessageFor(problemError(401, 'auth.account_inactive')).key).toBe('mobile.verify.errors.inactive');
    expect(authMessageFor(new ApiRequestError(undefined)).key).toBe('mobile.verify.errors.network');
    expect(authMessageFor(new ApiRequestError(503)).key).toBe('mobile.verify.errors.network');
  });

  it('is generic for anything else, and has no retry delay', () => {
    expect(authMessageFor(new Error('x')).key).toBe('mobile.verify.errors.generic');
    expect(authMessageFor(problemError(400, 'validation_failed')).key).toBe('mobile.verify.errors.generic');
    expect(retryAfterOf(problemError(400, 'auth.invalid_code'))).toBeUndefined();
  });

  it('only returns keys that exist in the catalog', () => {
    for (const error of [
      problemError(400, 'auth.invalid_code'),
      problemError(429, 'auth.rate_limited', 5),
      problemError(401, 'auth.account_inactive'),
      new ApiRequestError(undefined),
      new Error('x'),
    ]) {
      expect(resolve(authMessageFor(error).key)).toBeTypeOf('string');
    }
  });
});

describe('session end', () => {
  it('derives the reason from the problem code', () => {
    expect(endReasonFor('auth.session_revoked')).toBe('revoked');
    expect(endReasonFor('auth.account_inactive')).toBe('inactive');
    expect(endReasonFor('auth.invalid_refresh_token')).toBe('expired');
    expect(endReasonFor(undefined)).toBe('expired');
  });

  it('knows the terminal codes', () => {
    expect(isTerminalSessionCode('auth.session_revoked')).toBe(true);
    expect(isTerminalSessionCode('auth.account_inactive')).toBe(true);
    expect(isTerminalSessionCode('auth.invalid_token')).toBe(false);
    expect(isTerminalSessionCode(undefined)).toBe(false);
  });

  it('has a catalog message for every reason', () => {
    for (const reason of ['expired', 'revoked', 'inactive'] as const) {
      expect(resolve(endNoticeKey(reason))).toBeTypeOf('string');
    }
  });
});

describe('studentCodeMessageFor', () => {
  it('gives every wrong, expired or revoked code the same friendly answer', () => {
    expect(studentCodeMessageFor(problemError(400, 'auth.invalid_code'))).toEqual({ key: 'mobile.studentCode.errors.invalid' });
    expect(resolve('mobile.studentCode.errors.invalid')).toBe("That code didn't work. Ask your teacher for a new one.");
  });

  it('counts down the rate limit', () => {
    expect(studentCodeMessageFor(problemError(429, 'auth.rate_limited', 90))).toEqual({
      key: 'mobile.studentCode.errors.rateLimited',
      values: { seconds: 90 },
    });
    expect(studentCodeRetryAfter(problemError(429, 'auth.rate_limited', 90))).toBe(90);
    expect(studentCodeRetryAfter(problemError(429, 'something_else'))).toBe(60);
    expect(studentCodeRetryAfter(problemError(400, 'auth.invalid_code'))).toBeUndefined();
  });

  it('separates network trouble from other failures', () => {
    expect(studentCodeMessageFor(new ApiRequestError(undefined))).toEqual({ key: 'mobile.studentCode.errors.network' });
    expect(studentCodeMessageFor(problemError(503, 'unavailable'))).toEqual({ key: 'mobile.studentCode.errors.network' });
    expect(studentCodeMessageFor(problemError(403, 'auth.account_inactive'))).toEqual({ key: 'mobile.studentCode.errors.inactive' });
    expect(studentCodeMessageFor(new Error('boom'))).toEqual({ key: 'mobile.studentCode.errors.generic' });
  });
});
