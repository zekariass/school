import { describe, expect, it } from 'vitest';

import { createApiClient, isProblemDetail } from './index';

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
    const { fetch, requests } = recordingFetch({ status: 'UP' });
    const api = createApiClient({ baseUrl: 'http://api.test', fetch });

    const { data, error } = await api.GET('/actuator/health');

    expect(error).toBeUndefined();
    expect(data?.status).toBe('UP');
    expect(requests[0]?.url).toBe('http://api.test/actuator/health');
  });

  it('adds bearer token and Accept-Language when available', async () => {
    const { fetch, requests } = recordingFetch({ status: 'UP' });
    const api = createApiClient({
      baseUrl: 'http://api.test',
      fetch,
      getAccessToken: async () => 'tok123',
      getLocale: () => 'am',
    });

    await api.GET('/actuator/health');

    expect(requests[0]?.headers.get('Authorization')).toBe('Bearer tok123');
    expect(requests[0]?.headers.get('Accept-Language')).toBe('am');
  });

  it('omits Authorization when signed out', async () => {
    const { fetch, requests } = recordingFetch({ status: 'UP' });
    const api = createApiClient({ baseUrl: 'http://api.test', fetch, getAccessToken: () => null });

    await api.GET('/actuator/health');

    expect(requests[0]?.headers.has('Authorization')).toBe(false);
  });

  it('surfaces error bodies', async () => {
    const { fetch } = recordingFetch({ status: 'DOWN' }, 503);
    const api = createApiClient({ baseUrl: 'http://api.test', fetch });

    const { data, error } = await api.GET('/actuator/health');

    expect(data).toBeUndefined();
    expect(error).toEqual({ status: 'DOWN' });
  });
});

describe('isProblemDetail', () => {
  it('recognizes RFC 9457 bodies', () => {
    expect(isProblemDetail({ title: 'Forbidden', status: 403, code: 'class.access_denied' })).toBe(true);
    expect(isProblemDetail('nope')).toBe(false);
    expect(isProblemDetail(null)).toBe(false);
  });
});
