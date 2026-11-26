import type { CacheHit, CacheKind } from './cache';

/**
 * "Network first, cache when the network is unusable": the one rule behind every offline read of the class screens.
 *
 * - A successful call is written through to the cache (best effort: a failing write never breaks the screen).
 * - A call that could not reach the server (no answer, a timeout, 5xx, 429, an expired session that cannot be renewed
 *   right now) is answered from the cache when it holds an entry that is not too old, marked `fromCache`.
 * - 404 means the class is gone for this teacher: everything cached about it is deleted, and the error is raised.
 *   (403 is not a reason to delete: it is also what a disabled feature answers.) Any other 4xx is raised as it is.
 */

export interface Cached<T> {
  readonly data: T;
  /** Epoch ms when the server last confirmed this data. */
  readonly fetchedAt: number;
  readonly fromCache: boolean;
}

export interface CachedFetchSpec<T> {
  readonly kind: CacheKind;
  readonly scope: string;
  readonly entryKey?: string;
  readonly fetch: () => Promise<T>;
  /** What may be written to the cache (see `cache.ts`: explicit, minimal fields). */
  readonly toCache: (data: T) => unknown;
  /** The data of a cache entry, or null when the entry is not usable. */
  readonly fromCache: (stored: unknown) => T | null;
}

export interface CacheLike {
  get(kind: CacheKind, scope: string, entryKey?: string): Promise<CacheHit<unknown> | null>;
  put(kind: CacheKind, scope: string, entryKey: string, data: unknown, fetchedAt?: number): Promise<void>;
  forgetClass(scope: string): Promise<void>;
}

/** Whether a failed call says "the server could not be asked" rather than "the server said no". */
export function isUnreachable(status: number | undefined): boolean {
  return status === undefined || status >= 500 || status === 401 || status === 408 || status === 425 || status === 429;
}

export function statusOf(error: unknown): number | undefined {
  if (typeof error === 'object' && error !== null && 'status' in error) {
    const { status } = error as { status: unknown };
    return typeof status === 'number' ? status : undefined;
  }
  return undefined;
}

export async function fetchThroughCache<T>(cache: CacheLike, spec: CachedFetchSpec<T>, now: () => number = Date.now): Promise<Cached<T>> {
  const entryKey = spec.entryKey ?? '';
  try {
    const data = await spec.fetch();
    const fetchedAt = now();
    // Best effort: the screen has its data, a failed write only means no offline copy this time.
    void cache.put(spec.kind, spec.scope, entryKey, spec.toCache(data), fetchedAt).catch(() => {});
    return { data, fetchedAt, fromCache: false };
  } catch (error) {
    const status = statusOf(error);
    if (isUnreachable(status)) {
      const hit = await cache.get(spec.kind, spec.scope, entryKey).catch(() => null);
      const data = hit ? spec.fromCache(hit.data) : null;
      if (hit && data !== null) return { data, fetchedAt: hit.fetchedAt, fromCache: true };
    } else if (status === 404 && spec.scope !== '') {
      void cache.forgetClass(spec.scope).catch(() => {});
    }
    throw error;
  }
}
