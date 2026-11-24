import { describe, expect, it } from 'vitest';

import { createApiClient, isProblemDetail } from './index';

const PROBLEM = { type: 'urn:school:problem:internal_error', title: 'Server error', status: 503, code: 'internal_error' };
const META = { service: 'school-backend', apiVersion: 'v1', buildVersion: 'dev', serverTime: '2026-11-24T10:00:00Z', supportedLocales: ['en', 'am'] };

function recordingFetch(body: unknown, status = 200) {
  const requests: Request[] = [];
  const fetch = async (input: Request) => {
    requests.push(input);
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  };
  return { fetch: fetch as unknown as typeof globalThis.fetch, requests };
}

describe('createApiClient', () => {
  it('calls typed endpoints and returns data', async () => {
    const { fetch, requests } = recordingFetch(META);
    const api = createApiClient({ baseUrl: 'http://api.test', fetch });

    const { data, error } = await api.GET('/api/v1/meta');

    expect(error).toBeUndefined();
    expect(data?.apiVersion).toBe('v1');
    expect(data?.supportedLocales).toContain('am');
    expect(requests[0]?.url).toBe('http://api.test/api/v1/meta');
  });

  it('adds bearer token and Accept-Language when available', async () => {
    const { fetch, requests } = recordingFetch(META);
    const api = createApiClient({
      baseUrl: 'http://api.test',
      fetch,
      getAccessToken: async () => 'tok123',
      getLocale: () => 'am',
    });

    await api.GET('/api/v1/meta');

    expect(requests[0]?.headers.get('Authorization')).toBe('Bearer tok123');
    expect(requests[0]?.headers.get('Accept-Language')).toBe('am');
  });

  it('omits Authorization when signed out', async () => {
    const { fetch, requests } = recordingFetch(META);
    const api = createApiClient({ baseUrl: 'http://api.test', fetch, getAccessToken: () => null });

    await api.GET('/api/v1/meta');

    expect(requests[0]?.headers.has('Authorization')).toBe(false);
  });

  it('surfaces error bodies', async () => {
    const { fetch } = recordingFetch(PROBLEM, 503);
    const api = createApiClient({ baseUrl: 'http://api.test', fetch });

    const { data, error } = await api.GET('/api/v1/meta');

    expect(data).toBeUndefined();
    expect(error).toEqual(PROBLEM);
    expect(isProblemDetail(error)).toBe(true);
  });
});

describe('isProblemDetail', () => {
  it('recognizes RFC 9457 bodies', () => {
    expect(isProblemDetail({ title: 'Forbidden', status: 403, code: 'class.access_denied' })).toBe(true);
    expect(isProblemDetail('nope')).toBe(false);
    expect(isProblemDetail(null)).toBe(false);
  });
});
