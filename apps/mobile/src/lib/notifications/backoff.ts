const BASE_MS = 1000;
const MAX_MS = 30_000;

/**
 * Reconnect delay after `attempt` failures in a row (0 = first retry): exponential from 1 s
 * to 30 s with "equal jitter" (between half and all of the delay), so a restarted server is
 * not hit by every phone at once. `random` is injectable for tests.
 */
export function reconnectDelayMs(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(MAX_MS, BASE_MS * 2 ** Math.max(0, Math.min(attempt, 10)));
  return Math.round(ceiling / 2 + random() * (ceiling / 2));
}
