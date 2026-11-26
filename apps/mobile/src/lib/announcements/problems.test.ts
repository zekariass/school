import type { ProblemDetail } from '@school/api-client';
import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { codeOf, isGone, remindProblemKind, retryAfterHours, sendProblemKind } from './problems';

const problem = (status: number, code: string, retryAfterSeconds?: number) =>
  new ApiRequestError(status, { title: 'x', status, code, detail: 'localized' } as ProblemDetail, { retryAfterSeconds });

describe('sendProblemKind', () => {
  it('knows nobody to send to (422), the rate limit (429) and no connection', () => {
    expect(sendProblemKind(problem(422, 'announcement.no_recipients'))).toBe('no_recipients');
    expect(sendProblemKind(problem(429, 'announcement.rate_limited'))).toBe('rate_limited');
    expect(sendProblemKind(new ApiRequestError(undefined))).toBe('offline');
  });
  it('knows a withdrawn announcement and a forbidden one', () => {
    expect(sendProblemKind(problem(409, 'announcement.withdrawn'))).toBe('withdrawn');
    expect(sendProblemKind(problem(403, 'announcement.audience_not_allowed'))).toBe('forbidden');
  });
  it('treats anything else as other', () => {
    expect(sendProblemKind(problem(400, 'announcement.title_required'))).toBe('other');
    expect(sendProblemKind(new Error('boom'))).toBe('other');
  });
});

describe('remindProblemKind', () => {
  it('tells the four refusals apart', () => {
    expect(remindProblemKind(problem(429, 'announcement.reminder_too_soon', 3600))).toBe('too_soon');
    expect(remindProblemKind(problem(409, 'announcement.reminder_limit_reached'))).toBe('limit_reached');
    expect(remindProblemKind(problem(409, 'announcement.nobody_to_remind'))).toBe('nobody');
    expect(remindProblemKind(problem(409, 'announcement.ack_not_required'))).toBe('not_required');
  });
  it('a per-user rate limit and a lost connection', () => {
    expect(remindProblemKind(problem(429, 'rate_limited'))).toBe('rate_limited');
    expect(remindProblemKind(new ApiRequestError(undefined))).toBe('offline');
  });
});

describe('helpers', () => {
  it('reads the code of a problem', () => {
    expect(codeOf(problem(404, 'announcement.not_found'))).toBe('announcement.not_found');
    expect(codeOf(new ApiRequestError(500))).toBeUndefined();
    expect(codeOf(null)).toBeUndefined();
  });
  it('rounds Retry-After up to whole hours', () => {
    expect(retryAfterHours(undefined)).toBeUndefined();
    expect(retryAfterHours(30)).toBe(1);
    expect(retryAfterHours(3600)).toBe(1);
    expect(retryAfterHours(3601)).toBe(2);
    expect(retryAfterHours(20 * 3600)).toBe(20);
  });
  it('404 and 403 mean "not for you or gone"', () => {
    expect(isGone(new ApiRequestError(404))).toBe(true);
    expect(isGone(new ApiRequestError(403))).toBe(true);
    expect(isGone(new ApiRequestError(500))).toBe(false);
  });
});
