import { singleFlight } from './single-flight';

export interface IssuedTokens {
  accessToken: string;
  accessTokenExpiresIn: number;
  refreshToken: string;
}

/** What one call to POST /auth/refresh amounted to. */
export type RefreshCallResult =
  | { kind: 'ok'; tokens: IssuedTokens }
  /** The server refused this refresh token (400/401/403): the stored token is dead. */
  | { kind: 'rejected'; code: string | undefined }
  /** Offline, timeout, 5xx or 429: the token may still be good. */
  | { kind: 'unavailable' };

export type RefreshOutcome =
  | { status: 'refreshed'; accessToken: string }
  | { status: 'ended'; code: string | undefined }
  | { status: 'unavailable' }
  | { status: 'no-token' };

export interface RefresherDeps {
  readRefreshToken: () => Promise<string | null>;
  callRefresh: (refreshToken: string) => Promise<RefreshCallResult>;
  /** Persists the rotated pair (refresh token to secure storage, access token to memory). */
  storeTokens: (tokens: IssuedTokens) => Promise<void>;
  clearTokens: () => Promise<void>;
  /** The refresh token was refused: the user must sign in again. */
  onEnded: (code: string | undefined) => void;
}

/**
 * The one place that exchanges the refresh token. The returned function is
 * single-flight: concurrent callers (parallel 401s, a proactive refresh racing a
 * restore) share one request, because presenting a rotated token twice revokes the session.
 *
 * Network trouble never discards the token: the outcome is `unavailable` and the caller
 * decides (use the old access token, show an offline screen).
 */
export function createTokenRefresher(deps: RefresherDeps): () => Promise<RefreshOutcome> {
  return singleFlight(async (): Promise<RefreshOutcome> => {
    const refreshToken = await deps.readRefreshToken();
    if (!refreshToken) return { status: 'no-token' };

    const result = await deps.callRefresh(refreshToken);
    if (result.kind === 'unavailable') return { status: 'unavailable' };

    if (result.kind === 'rejected') {
      await deps.clearTokens();
      deps.onEnded(result.code);
      return { status: 'ended', code: result.code };
    }

    try {
      await deps.storeTokens(result.tokens);
    } catch {
      // The rotated refresh token could not be written. The access token is already in
      // memory, so this run of the app keeps working; the next launch will need a new sign-in.
    }
    return { status: 'refreshed', accessToken: result.tokens.accessToken };
  });
}
