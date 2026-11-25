export const BACKOFF_BASE_MS = 1_000;
export const BACKOFF_MAX_MS = 60_000;

/**
 * Delay before reconnect attempt number `attempt` (0 = first retry): exponential from 1 s to
 * 60 s with "equal jitter" (half fixed, half random), so a restarted server is not hit by
 * every browser at the same instant. `random` is injectable for tests.
 */
export function reconnectDelay(attempt: number, random: () => number = Math.random): number {
  const exponential = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** Math.max(0, Math.min(attempt, 16)));
  return Math.round(exponential / 2 + (exponential / 2) * random());
}
