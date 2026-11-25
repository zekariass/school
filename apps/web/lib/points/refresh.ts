/**
 * The class overview is eventually consistent: after an award or an undo the screen shows the optimistic
 * numbers and asks the API again a little later. Asking once per action would let an early, stale answer
 * overwrite a later action, so every action restarts the timers of its key: the API is asked 3 and 9 seconds
 * after the LAST action (the second look catches a slow projection).
 */
export const REFRESH_DELAYS_MS = [3000, 9000] as const;

export interface Refresher {
  /** (Re)starts the delayed refreshes of `key`; earlier timers of the same key are dropped. */
  schedule(key: string, run: () => void): void;
  /** Drops the timers of `key`. */
  cancel(key: string): void;
}

export function createRefresher(
  delays: readonly number[] = REFRESH_DELAYS_MS,
  timers: {
    set: (fn: () => void, ms: number) => unknown;
    clear: (handle: unknown) => void;
  } = { set: (fn, ms) => setTimeout(fn, ms), clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>) },
): Refresher {
  const pending = new Map<string, unknown[]>();
  const cancel = (key: string) => {
    for (const handle of pending.get(key) ?? []) timers.clear(handle);
    pending.delete(key);
  };
  return {
    schedule(key, run) {
      cancel(key);
      pending.set(
        key,
        delays.map((ms) => timers.set(run, ms)),
      );
    },
    cancel,
  };
}

/** One shared refresher for the browser tab. */
export const overviewRefresher = createRefresher();
