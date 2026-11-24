/**
 * Wraps an async operation so concurrent callers share one in-flight run.
 *
 * Refresh tokens rotate: the server revokes the whole session when an already-rotated
 * token is presented again, so two parallel refreshes with the same token would sign the
 * user out. Every caller that arrives while a run is pending gets that run's promise;
 * the next call after it settles starts a fresh run.
 */
export function singleFlight<T>(run: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;
  return () => {
    if (!inFlight) {
      inFlight = (async () => run())().finally(() => {
        inFlight = null;
      });
    }
    return inFlight;
  };
}
