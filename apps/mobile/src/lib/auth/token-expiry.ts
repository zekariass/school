/** Refresh the access token when it expires within this window (clock skew, slow networks). */
export const REFRESH_SKEW_MS = 60_000;

/** Wall-clock instant (ms) at which a token issued now with `expiresInSeconds` stops working. */
export function expiryFrom(nowMs: number, expiresInSeconds: number): number {
  return nowMs + Math.max(0, expiresInSeconds) * 1000;
}

/**
 * True when a request should wait for a refresh first: the access token expires
 * within `skewMs` (or already has). An unknown expiry is treated as "do not refresh":
 * there is no token to renew, and a 401 will still trigger the reactive path.
 */
export function needsProactiveRefresh(
  expiresAtMs: number | null,
  nowMs: number,
  skewMs: number = REFRESH_SKEW_MS,
): boolean {
  if (expiresAtMs === null) return false;
  return expiresAtMs - nowMs <= skewMs;
}
