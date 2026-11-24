import { tokensFromResponse, type SessionTokens } from "./session-core";

/**
 * Exchanges a refresh token for a new pair at the API, shared by `proxy.ts` and
 * the BFF route handler.
 *
 * The API rotates refresh tokens and treats a second use of an old one as theft
 * (it revokes the whole session). Parallel requests that all notice an expired
 * access token must therefore share ONE refresh: calls are deduplicated per
 * refresh token, and a finished result is kept for 5 seconds so requests
 * that arrive a moment later with the same (now rotated) cookie reuse it.
 *
 * Caveat: the dedupe store is in process memory (on `globalThis`, so the
 * proxy and route handler bundles of one Node process share it). Behind several
 * web instances, two requests of the same browser landing on different
 * instances can still race and trip reuse detection. Use sticky sessions, or
 * add a grace window for the previous token in the API, before scaling out.
 */

export type RefreshOutcome =
  | { status: "refreshed"; tokens: SessionTokens }
  /** The API refused the token (expired, revoked, reused): the session is over. */
  | { status: "rejected" }
  /** Network error, timeout or 5xx/429: the session may still be fine. */
  | { status: "unavailable" };

const RESULT_TTL_MS = 5_000;
const REQUEST_TIMEOUT_MS = 10_000;

interface Store {
  entries: Map<string, { promise: Promise<RefreshOutcome>; settledAt?: number }>;
}

const STORE_KEY = Symbol.for("school.web.refresh-store");

function getStore(): Store {
  const g = globalThis as unknown as Record<symbol, Store | undefined>;
  return (g[STORE_KEY] ??= { entries: new Map() });
}

export interface RefreshDeps {
  fetch?: typeof fetch;
  now?: () => number;
  /** Accept-Language for the API; irrelevant to the result. */
  locale?: string;
}

export function refreshSession(
  apiOrigin: string,
  refreshToken: string,
  deps: RefreshDeps = {},
): Promise<RefreshOutcome> {
  const now = deps.now ?? Date.now;
  const store = getStore();
  const nowMs = now();

  // Drop stale results so the map cannot grow without bound.
  for (const [key, entry] of store.entries) {
    if (entry.settledAt !== undefined && nowMs - entry.settledAt > RESULT_TTL_MS) {
      store.entries.delete(key);
    }
  }

  const existing = store.entries.get(refreshToken);
  if (existing) return existing.promise;

  const entry: { promise: Promise<RefreshOutcome>; settledAt?: number } = {
    promise: callRefresh(apiOrigin, refreshToken, deps).then((outcome) => {
      entry.settledAt = now();
      // Failures are not worth replaying: let the next request retry.
      if (outcome.status === "unavailable") store.entries.delete(refreshToken);
      return outcome;
    }),
  };
  store.entries.set(refreshToken, entry);
  return entry.promise;
}

async function callRefresh(
  apiOrigin: string,
  refreshToken: string,
  deps: RefreshDeps,
): Promise<RefreshOutcome> {
  const doFetch = deps.fetch ?? fetch;
  try {
    const response = await doFetch(`${apiOrigin.replace(/\/+$/, "")}/api/v1/auth/refresh`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        accept: "application/json",
        ...(deps.locale ? { "accept-language": deps.locale } : {}),
      },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (response.ok) {
      const tokens = tokensFromResponse(await response.json().catch(() => null));
      return tokens ? { status: "refreshed", tokens } : { status: "unavailable" };
    }
    if (response.status === 429 || response.status >= 500) return { status: "unavailable" };
    return { status: "rejected" };
  } catch {
    return { status: "unavailable" };
  }
}

/** Test hook. */
export function resetRefreshStore(): void {
  getStore().entries.clear();
}
