import { describe, expect, it, vi } from 'vitest';

import { createAuthFetch, decideOnUnauthorized, type AuthFetchDeps } from './auth-fetch';
import type { RefreshOutcome } from './refresh';
import { singleFlight } from './single-flight';

function problem(status: number, code: string): Response {
  return new Response(JSON.stringify({ status, code, title: code, type: 'about:blank' }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

describe('decideOnUnauthorized (the retry-once rule)', () => {
  const base = {
    hadAuthorization: true,
    alreadyRetried: false,
    code: 'auth.invalid_token',
    sentToken: 'old',
    currentToken: 'old',
  };

  it('refreshes and retries an expired token', () => {
    expect(decideOnUnauthorized(base)).toBe('refresh-and-retry');
  });

  it('reuses a token another request already renewed instead of refreshing again', () => {
    expect(decideOnUnauthorized({ ...base, currentToken: 'new' })).toBe('retry-with-current-token');
  });

  it('never retries twice', () => {
    expect(decideOnUnauthorized({ ...base, alreadyRetried: true })).toBe('pass');
  });

  it('ends the session on a terminal code, even on the retry', () => {
    for (const code of ['auth.session_revoked', 'auth.account_inactive']) {
      expect(decideOnUnauthorized({ ...base, code })).toBe('end-session');
      expect(decideOnUnauthorized({ ...base, code, alreadyRetried: true })).toBe('end-session');
    }
  });

  it('leaves unauthenticated requests and signed-out sessions alone', () => {
    expect(decideOnUnauthorized({ ...base, hadAuthorization: false })).toBe('pass');
    expect(decideOnUnauthorized({ ...base, currentToken: null })).toBe('pass');
  });
});

function setup(responses: Response[], opts: { current?: () => string | null; refresh?: () => Promise<RefreshOutcome> } = {}) {
  let token: string | null = 'old';
  const seen: Request[] = [];
  const queue = [...responses];
  const baseFetch = vi.fn(async (input: RequestInfo | URL) => {
    seen.push(input as Request);
    return queue.shift() ?? new Response('{}', { status: 200 });
  });
  const refresh = vi.fn(
    opts.refresh ??
      (async (): Promise<RefreshOutcome> => {
        token = 'new';
        return { status: 'refreshed', accessToken: 'new' };
      }),
  );
  const endSession = vi.fn(async () => {
    token = null;
  });
  const deps: AuthFetchDeps = {
    baseFetch: baseFetch as unknown as typeof fetch,
    getAccessToken: opts.current ?? (() => token),
    refresh,
    endSession,
  };
  return { fetch: createAuthFetch(deps), baseFetch, refresh, endSession, seen };
}

const authed = (body?: string) =>
  new Request('https://api.test/api/v1/things', {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: 'Bearer old', 'Content-Type': 'application/json' },
    ...(body ? { body } : {}),
  });

describe('createAuthFetch', () => {
  it('passes successful responses through untouched', async () => {
    const t = setup([new Response('{"ok":true}', { status: 200 })]);
    const res = await t.fetch(authed());
    expect(res.status).toBe(200);
    expect(t.refresh).not.toHaveBeenCalled();
  });

  it('refreshes once and retries once with the new token, body intact', async () => {
    const t = setup([problem(401, 'auth.invalid_token'), new Response('{"ok":true}', { status: 200 })]);
    const res = await t.fetch(authed('{"a":1}'));

    expect(res.status).toBe(200);
    expect(t.refresh).toHaveBeenCalledTimes(1);
    expect(t.baseFetch).toHaveBeenCalledTimes(2);
    expect(t.seen[1]!.headers.get('Authorization')).toBe('Bearer new');
    expect(await t.seen[1]!.text()).toBe('{"a":1}');
  });

  it('shares one refresh between parallel 401s', async () => {
    let token = 'old';
    let gateOpen!: () => void;
    const gate = new Promise<void>((resolve) => (gateOpen = resolve));
    let refreshRuns = 0;
    const refresh = singleFlight(async (): Promise<RefreshOutcome> => {
      refreshRuns++;
      await gate;
      token = 'new';
      return { status: 'refreshed', accessToken: 'new' };
    });
    let rejected = 0;
    const baseFetch = vi.fn(async (input: RequestInfo | URL) => {
      if ((input as Request).headers.get('Authorization') === 'Bearer old') {
        rejected++;
        return problem(401, 'auth.invalid_token');
      }
      return new Response('{}', { status: 200 });
    });
    const f = createAuthFetch({
      baseFetch: baseFetch as unknown as typeof fetch,
      getAccessToken: () => token,
      refresh,
      endSession: vi.fn(),
    });

    const pending = Promise.all([f(authed()), f(authed()), f(authed())]);
    // Hold the refresh open until all three requests have been rejected and are waiting on it.
    await vi.waitFor(() => expect(rejected).toBe(3));
    gateOpen();
    const responses = await pending;

    expect(responses.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(refreshRuns).toBe(1);
    expect(baseFetch).toHaveBeenCalledTimes(6); // 3 rejected + 3 retried, never a second retry
  });

  it('does not refresh again when another request already renewed the token', async () => {
    const t = setup([problem(401, 'auth.invalid_token'), new Response('{}', { status: 200 })], {
      current: () => 'new',
    });
    const res = await t.fetch(authed());
    expect(res.status).toBe(200);
    expect(t.refresh).not.toHaveBeenCalled();
    expect(t.seen[1]!.headers.get('Authorization')).toBe('Bearer new');
  });

  it('returns the second 401 instead of looping', async () => {
    const t = setup([problem(401, 'auth.invalid_token'), problem(401, 'auth.invalid_token')]);
    const res = await t.fetch(authed());
    expect(res.status).toBe(401);
    expect(t.baseFetch).toHaveBeenCalledTimes(2);
    expect(t.refresh).toHaveBeenCalledTimes(1);
    expect(t.endSession).not.toHaveBeenCalled();
  });

  it('returns the original 401 when the refresh is refused or the network is down', async () => {
    for (const status of ['ended', 'unavailable', 'no-token'] as const) {
      const t = setup([problem(401, 'auth.invalid_token')], {
        refresh: async () => (status === 'ended' ? { status, code: 'auth.invalid_refresh_token' } : { status }),
      });
      const res = await t.fetch(authed());
      expect(res.status).toBe(401);
      expect(t.baseFetch).toHaveBeenCalledTimes(1);
    }
  });

  it('signs out locally on a terminal 401 without refreshing', async () => {
    const t = setup([problem(401, 'auth.account_inactive')]);
    const res = await t.fetch(authed());
    expect(res.status).toBe(401);
    expect(t.refresh).not.toHaveBeenCalled();
    expect(t.endSession).toHaveBeenCalledWith('auth.account_inactive');
  });

  it('signs out when the retried request reports a revoked session', async () => {
    const t = setup([problem(401, 'auth.invalid_token'), problem(401, 'auth.session_revoked')]);
    await t.fetch(authed());
    expect(t.endSession).toHaveBeenCalledWith('auth.session_revoked');
  });

  it('ignores 401s on requests that carried no token', async () => {
    const t = setup([problem(401, 'unauthorized')]);
    const res = await t.fetch(new Request('https://api.test/api/v1/auth/otp/verify', { method: 'POST', body: '{}' }));
    expect(res.status).toBe(401);
    expect(t.refresh).not.toHaveBeenCalled();
    expect(t.endSession).not.toHaveBeenCalled();
  });
});
