import type { ProblemDetail } from '@school/api-client';
import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { classifyReportFailure, retryAfterParts } from './failure';

const problem = (status: number, code: string, detail: string): ProblemDetail => ({ status, code, detail, title: 'x', type: 'about:blank' });

describe('classifyReportFailure', () => {
  it('shows a 404 as plain "not found", whatever the API said: a hidden target looks like a missing one', () => {
    const failure = classifyReportFailure(new ApiRequestError(404, problem(404, 'moderation.target_not_found', 'a sentence that must not be shown')));
    expect(failure).toEqual({ kind: 'notFound' });
    expect(JSON.stringify(failure)).not.toContain('sentence');
  });
  it('treats a 404 without a problem body the same way', () => {
    expect(classifyReportFailure(new ApiRequestError(404))).toEqual({ kind: 'notFound' });
  });
  it('shows the API sentence for your own content (409), a refused detail (400) and a switched-off school (403)', () => {
    for (const status of [400, 403, 409]) {
      expect(classifyReportFailure(new ApiRequestError(status, problem(status, 'c', 'The API sentence.')))).toEqual({ kind: 'refused', detail: 'The API sentence.' });
    }
  });
  it('carries Retry-After for 429', () => {
    const error = new ApiRequestError(429, problem(429, 'moderation.report_rate_limited', 'Many reports.'), { retryAfterSeconds: 3600 });
    expect(classifyReportFailure(error)).toEqual({ kind: 'rateLimited', detail: 'Many reports.', retryAfterSeconds: 3600 });
  });
  it('treats a missing answer as offline, and a 5xx as a failure to retry', () => {
    expect(classifyReportFailure(new ApiRequestError(undefined))).toEqual({ kind: 'offline' });
    expect(classifyReportFailure(new TypeError('network'))).toEqual({ kind: 'offline' });
    expect(classifyReportFailure(new ApiRequestError(503, problem(503, 'c', 'Busy.')))).toEqual({ kind: 'failed', detail: 'Busy.' });
  });
});

describe('retryAfterParts', () => {
  it('uses seconds, minutes or hours without a plural', () => {
    expect(retryAfterParts(0)).toEqual({ unit: 'seconds', value: 1 });
    expect(retryAfterParts(30)).toEqual({ unit: 'seconds', value: 30 });
    expect(retryAfterParts(600)).toEqual({ unit: 'minutes', value: 10 });
    expect(retryAfterParts(86_400)).toEqual({ unit: 'hours', value: 24 });
  });
});
