import { describe, expect, it } from 'vitest';

import { ApiRequestError, parseRetryAfter, problemOf, request, shouldRetry, unwrap } from './api-error';

const response = (status: number) => ({ status }) as Response;

describe('unwrap', () => {
  it('returns data on success', () => {
    expect(unwrap({ data: { ok: true }, response: response(200) })).toEqual({ ok: true });
  });

  it('throws an ApiRequestError carrying the problem', () => {
    const problem = { title: 'Not found', status: 404, code: 'common.not_found' };
    try {
      unwrap({ error: problem, response: response(404) });
      expect.unreachable();
    } catch (error) {
      expect(error).toBeInstanceOf(ApiRequestError);
      expect((error as ApiRequestError).status).toBe(404);
      expect(problemOf(error)).toEqual(problem);
    }
  });

  it('ignores error bodies that are not problem details', () => {
    expect(() => unwrap({ error: 'oops', response: response(502) })).toThrow(ApiRequestError);
    try {
      unwrap({ error: 'oops', response: response(502) });
    } catch (error) {
      expect(problemOf(error)).toBeUndefined();
    }
  });
});

describe('request', () => {
  it('wraps transport failures without a status', async () => {
    const cause = new TypeError('Network request failed');
    await expect(request(() => Promise.reject(cause))).rejects.toMatchObject({
      name: 'ApiRequestError',
      status: undefined,
      cause,
    });
  });
});

describe('shouldRetry', () => {
  it('never retries 4xx problems', () => {
    expect(shouldRetry(0, new ApiRequestError(404))).toBe(false);
    expect(shouldRetry(0, new ApiRequestError(401))).toBe(false);
  });

  it('retries network and 5xx failures twice', () => {
    expect(shouldRetry(0, new ApiRequestError(undefined))).toBe(true);
    expect(shouldRetry(1, new ApiRequestError(503))).toBe(true);
    expect(shouldRetry(2, new ApiRequestError(503))).toBe(false);
  });
});

describe('parseRetryAfter', () => {
  it('reads delta-seconds', () => {
    expect(parseRetryAfter('42')).toBe(42);
    expect(parseRetryAfter('0')).toBe(1);
  });

  it('reads an HTTP date relative to now', () => {
    const now = Date.parse('2026-11-24T10:00:00Z');
    expect(parseRetryAfter('Tue, 24 Nov 2026 10:01:30 GMT', now)).toBe(90);
    expect(parseRetryAfter('Tue, 24 Nov 2026 09:00:00 GMT', now)).toBe(1);
  });

  it('is undefined when absent or unreadable', () => {
    expect(parseRetryAfter(null)).toBeUndefined();
    expect(parseRetryAfter('')).toBeUndefined();
    expect(parseRetryAfter('soon')).toBeUndefined();
  });

  it('is carried on the error by unwrap', () => {
    const response = new Response('{}', { status: 429, headers: { 'Retry-After': '17' } });
    try {
      unwrap({ error: { code: 'auth.rate_limited', status: 429, title: 't', type: 'x' }, response });
      expect.unreachable();
    } catch (error) {
      expect((error as ApiRequestError).retryAfterSeconds).toBe(17);
    }
  });
});
