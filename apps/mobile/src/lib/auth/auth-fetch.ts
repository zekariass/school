import { isTerminalSessionCode } from './session-events';
import type { RefreshOutcome } from './refresh';

export type UnauthorizedAction =
  /** Not ours to handle: hand the response back unchanged. */
  | 'pass'
  /** The server says the session is over (revoked, account inactive): sign out locally. */
  | 'end-session'
  /** Another request already renewed the token: retry with it, no new refresh. */
  | 'retry-with-current-token'
  /** The access token expired: refresh once (shared with concurrent 401s), then retry. */
  | 'refresh-and-retry';

export interface UnauthorizedContext {
  /** The failed request carried an Authorization header. */
  hadAuthorization: boolean;
  /** This is already the retry: never retry twice. */
  alreadyRetried: boolean;
  /** Problem code of the 401 body, if any. */
  code: string | undefined;
  sentToken: string | null;
  /** The access token held in memory now. */
  currentToken: string | null;
}

/** The retry-once rule for a 401 on an authenticated request. */
export function decideOnUnauthorized(ctx: UnauthorizedContext): UnauthorizedAction {
  if (!ctx.hadAuthorization) return 'pass';
  if (isTerminalSessionCode(ctx.code)) return 'end-session';
  if (ctx.alreadyRetried) return 'pass';
  if (ctx.currentToken === null) return 'pass'; // signed out meanwhile
  if (ctx.currentToken !== ctx.sentToken) return 'retry-with-current-token';
  return 'refresh-and-retry';
}

export interface AuthFetchDeps {
  baseFetch: typeof fetch;
  getAccessToken: () => string | null;
  refresh: () => Promise<RefreshOutcome>;
  /** Local sign-out for a terminal 401: clear tokens and tell the session provider. */
  endSession: (code: string | undefined) => Promise<void> | void;
}

/** Problem code of a 401 body without consuming the response. */
export async function problemCodeOf(response: Response): Promise<string | undefined> {
  try {
    const body: unknown = await response.clone().json();
    if (typeof body === 'object' && body !== null && 'code' in body && typeof body.code === 'string') {
      return body.code;
    }
  } catch {
    // Not JSON: no code.
  }
  return undefined;
}

function bearerOf(request: Request): string | null {
  const header = request.headers.get('Authorization');
  return header?.startsWith('Bearer ') ? header.slice('Bearer '.length) : null;
}

/**
 * A `fetch` that renews an expired access token transparently: on a 401 for an
 * authenticated request it refreshes (single-flight, via `deps.refresh`) and retries the
 * request exactly once with the new token. A terminal 401 signs the user out locally.
 */
export function createAuthFetch(deps: AuthFetchDeps): typeof fetch {
  return async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const sentToken = bearerOf(request);
    const hadAuthorization = request.headers.has('Authorization');
    // The body can be read once, so keep a pristine copy for a possible retry.
    const pristine = hadAuthorization ? request.clone() : null;

    const response = await deps.baseFetch(request, init);
    if (response.status !== 401 || !pristine) return response;

    const code = await problemCodeOf(response);
    const action = decideOnUnauthorized({
      hadAuthorization,
      alreadyRetried: false,
      code,
      sentToken,
      currentToken: deps.getAccessToken(),
    });

    switch (action) {
      case 'pass':
        return response;
      case 'end-session':
        await deps.endSession(code);
        return response;
      case 'retry-with-current-token':
        return retryWith(deps, pristine, deps.getAccessToken(), init);
      case 'refresh-and-retry': {
        const outcome = await deps.refresh();
        // `ended` is already reported by the refresher; `unavailable`/`no-token` leave the original 401.
        if (outcome.status !== 'refreshed') return response;
        return retryWith(deps, pristine, outcome.accessToken, init);
      }
    }
  };
}

async function retryWith(
  deps: AuthFetchDeps,
  pristine: Request,
  token: string | null,
  init: RequestInit | undefined,
): Promise<Response> {
  if (!token) return deps.baseFetch(pristine, init);
  const headers = new Headers(pristine.headers);
  headers.set('Authorization', `Bearer ${token}`);
  const response = await deps.baseFetch(new Request(pristine, { headers }), init);
  if (response.status === 401) {
    // The retry is final. Only a terminal code changes anything: sign out.
    const code = await problemCodeOf(response);
    if (decideOnUnauthorized({
      hadAuthorization: true,
      alreadyRetried: true,
      code,
      sentToken: token,
      currentToken: deps.getAccessToken(),
    }) === 'end-session') {
      await deps.endSession(code);
    }
  }
  return response;
}
