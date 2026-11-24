import { describe, expect, it, vi } from 'vitest';

import { createTokenRefresher, type IssuedTokens, type RefreshCallResult, type RefresherDeps } from './refresh';

const rotated: IssuedTokens = { accessToken: 'at2', accessTokenExpiresIn: 900, refreshToken: 'rt2' };

function setup(overrides: Partial<RefresherDeps> = {}) {
  let stored: string | null = 'rt1';
  const deps: RefresherDeps = {
    readRefreshToken: vi.fn(async () => stored),
    callRefresh: vi.fn(async (): Promise<RefreshCallResult> => ({ kind: 'ok', tokens: rotated })),
    storeTokens: vi.fn(async (tokens: IssuedTokens) => {
      stored = tokens.refreshToken;
    }),
    clearTokens: vi.fn(async () => {
      stored = null;
    }),
    onEnded: vi.fn(),
    ...overrides,
  };
  return { deps, refresh: createTokenRefresher(deps), stored: () => stored };
}

describe('createTokenRefresher', () => {
  it('rotates the pair and reports the new access token', async () => {
    const { deps, refresh, stored } = setup();
    await expect(refresh()).resolves.toEqual({ status: 'refreshed', accessToken: 'at2' });
    expect(deps.callRefresh).toHaveBeenCalledWith('rt1');
    expect(stored()).toBe('rt2');
  });

  it('sends one request for concurrent callers (rotation would otherwise revoke the session)', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { deps, refresh } = setup({
      callRefresh: vi.fn(async (): Promise<RefreshCallResult> => {
        await gate;
        return { kind: 'ok', tokens: rotated };
      }),
    });

    const all = Promise.all([refresh(), refresh(), refresh(), refresh()]);
    release();
    const outcomes = await all;

    expect(deps.callRefresh).toHaveBeenCalledTimes(1);
    expect(outcomes.every((o) => o.status === 'refreshed')).toBe(true);
  });

  it('reports no-token without calling the API', async () => {
    const { deps, refresh } = setup({ readRefreshToken: vi.fn(async () => null) });
    await expect(refresh()).resolves.toEqual({ status: 'no-token' });
    expect(deps.callRefresh).not.toHaveBeenCalled();
  });

  it('clears the tokens and ends the session when the server rejects the token', async () => {
    const { deps, refresh, stored } = setup({
      callRefresh: vi.fn(async (): Promise<RefreshCallResult> => ({ kind: 'rejected', code: 'auth.session_revoked' })),
    });
    await expect(refresh()).resolves.toEqual({ status: 'ended', code: 'auth.session_revoked' });
    expect(deps.clearTokens).toHaveBeenCalledTimes(1);
    expect(deps.onEnded).toHaveBeenCalledWith('auth.session_revoked');
    expect(stored()).toBeNull();
  });

  it('keeps the token when the network is down', async () => {
    const { deps, refresh, stored } = setup({
      callRefresh: vi.fn(async (): Promise<RefreshCallResult> => ({ kind: 'unavailable' })),
    });
    await expect(refresh()).resolves.toEqual({ status: 'unavailable' });
    expect(deps.clearTokens).not.toHaveBeenCalled();
    expect(deps.onEnded).not.toHaveBeenCalled();
    expect(stored()).toBe('rt1');
  });

  it('still succeeds for this run when the rotated token cannot be persisted', async () => {
    const { refresh } = setup({
      storeTokens: vi.fn(async () => {
        throw new Error('keychain unavailable');
      }),
    });
    await expect(refresh()).resolves.toEqual({ status: 'refreshed', accessToken: 'at2' });
  });
});
