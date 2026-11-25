import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { invitationMessageFor, invitationProblemOf, retryAfterOfInvitation } from './problems';

const problem = (status: number, code: string, detail?: string) =>
  new ApiRequestError(status, { type: 'x', title: 'T', status, code, ...(detail ? { detail } : {}) });

describe('invitationProblemOf', () => {
  it('treats every dead invitation alike', () => {
    expect(invitationProblemOf(problem(404, 'invitation.not_found'))).toEqual({ kind: 'notFound' });
  });

  it('reads an unreachable server as a network problem', () => {
    expect(invitationProblemOf(new ApiRequestError(undefined))).toEqual({ kind: 'network' });
    expect(invitationProblemOf(new Error('boom'))).toEqual({ kind: 'network' });
  });

  it('carries the retry hint of a rate limit', () => {
    const error = new ApiRequestError(429, { type: 'x', title: 'T', status: 429, code: 'auth.rate_limited' }, { retryAfterSeconds: 42 });
    expect(invitationProblemOf(error)).toEqual({ kind: 'rateLimited', retryAfterSeconds: 42 });
    expect(retryAfterOfInvitation(error)).toBe(42);
    expect(retryAfterOfInvitation(problem(400, 'auth.invalid_code'))).toBeUndefined();
  });
});

describe('invitationMessageFor', () => {
  it('gives the contact that cannot be used its own message', () => {
    expect(invitationMessageFor(problem(409, 'invitation.contact_unavailable')).message).toEqual({ key: 'invite.contactUnavailable' });
  });

  it('gives a rate limit a retry hint', () => {
    const error = new ApiRequestError(429, { type: 'x', title: 'T', status: 429, code: 'auth.rate_limited' }, { retryAfterSeconds: 30 });
    expect(invitationMessageFor(error).message).toEqual({ key: 'invite.rateLimited', values: { seconds: 30 } });
  });

  it('shows the API detail for anything else', () => {
    expect(invitationMessageFor(problem(400, 'auth.invalid_code', 'That code is wrong.'))).toEqual({
      message: { key: 'invite.failed' },
      detail: 'That code is wrong.',
    });
  });

  it('says the connection failed when offline', () => {
    expect(invitationMessageFor(new ApiRequestError(undefined)).message).toEqual({ key: 'invite.loadFailed' });
  });
});
