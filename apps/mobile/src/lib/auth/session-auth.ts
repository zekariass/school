import { createAuthFetch } from './auth-fetch';
import { createTokenRefresher, type RefreshCallResult } from './refresh';
import { createSessionEndBus, endReasonFor } from './session-events';
import { needsProactiveRefresh } from './token-expiry';

import { authApi, fetchWithTimeout } from '@/lib/http';
import { clearTokens, getAccessToken, getAccessTokenExpiry, readRefreshToken, storeTokens } from '@/lib/session/tokens';

/** Fires when the API layer finds the session over (refresh refused, revoked, account inactive). */
export const sessionEnded = createSessionEndBus();

/** Local sign-out forced by the server: forget the tokens, tell the session provider. */
export async function endSession(code: string | undefined): Promise<void> {
  await clearTokens();
  sessionEnded.emit(endReasonFor(code));
}

async function callRefresh(refreshToken: string): Promise<RefreshCallResult> {
  try {
    const { data, error, response } = await authApi.POST('/api/v1/auth/refresh', { body: { refreshToken } });
    if (data) return { kind: 'ok', tokens: data };
    if (response.status === 400 || response.status === 401 || response.status === 403) {
      const code = typeof error === 'object' && error !== null && 'code' in error ? String(error.code) : undefined;
      return { kind: 'rejected', code };
    }
    return { kind: 'unavailable' };
  } catch {
    return { kind: 'unavailable' };
  }
}

/** Single-flight token refresh: safe to call from anywhere, any number of times at once. */
export const refreshTokens = createTokenRefresher({
  readRefreshToken,
  callRefresh,
  storeTokens,
  clearTokens,
  onEnded: (code) => sessionEnded.emit(endReasonFor(code)),
});

/**
 * Access token for the next request. Renews it first when it expires within 60 s; if the
 * renewal is impossible right now (offline) the current token is used and the server decides.
 */
export async function getValidAccessToken(): Promise<string | null> {
  const token = getAccessToken();
  // No token in memory but a refresh token on the phone: the app was opened offline (see session-provider), so the
  // first call after the network is back renews the session instead of going out unauthenticated.
  if (token && !needsProactiveRefresh(getAccessTokenExpiry(), Date.now())) return token;
  const outcome = await refreshTokens();
  if (outcome.status === 'refreshed') return outcome.accessToken;
  if (outcome.status === 'unavailable') return token;
  return null;
}

/** fetch for the authenticated client: refresh once on 401 and retry the request once. */
export const authFetch = createAuthFetch({
  baseFetch: fetchWithTimeout,
  getAccessToken,
  refresh: refreshTokens,
  endSession,
});
