import { useQuery, type QueryKey } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { fetchThroughCache, type Cached, type CachedFetchSpec } from './cached-fetch';
import { offlineCache, useOfflineOwner } from './runtime';

export interface CachedQueryOptions<T> extends Omit<CachedFetchSpec<T>, 'scope'> {
  queryKey: QueryKey;
  enabled: boolean;
  /** The class the data belongs to (empty for the class list). */
  scope: string;
  staleTime?: number;
}

export interface CachedQueryResult<T> {
  data: T | undefined;
  /** When the server last confirmed `data` (a cache entry keeps its own time). */
  fetchedAt: number | undefined;
  /** `data` comes from the offline cache: the server could not be asked. */
  fromCache: boolean;
  /** Nothing to show yet. */
  isPending: boolean;
  isFetching: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => Promise<unknown>;
}

/**
 * A TanStack query with an offline copy. The query is network first and falls back to the explicit cache (see
 * `cached-fetch.ts`); on top of that the cached copy is read at once, so a screen shows something while the network
 * is slow or absent. The query data itself is never persisted by TanStack (its keys are excluded): the cache table
 * is the only copy, owned by the signed-in teacher.
 */
export function useCachedQuery<T>(options: CachedQueryOptions<T>): CachedQueryResult<T> {
  const { queryKey, enabled, scope, kind, entryKey, staleTime = 60_000 } = options;
  const owner = useOfflineOwner();

  const query = useQuery<Cached<T>>({
    queryKey,
    enabled,
    // Data that came from the cache is never "fresh": a reconnect or the next mount asks the server again.
    staleTime: (q) => (q.state.data?.fromCache ? 0 : staleTime),
    queryFn: () => fetchThroughCache(offlineCache, { ...options, scope }),
  });

  // The cached copy, tagged with what it is a copy of: a seed for another class or date is never shown.
  const seedKey = `${kind}|${scope}|${entryKey ?? ''}`;
  const [seed, setSeed] = useState<{ key: string; cached: Cached<T> } | null>(null);
  const { fromCache } = options;
  useEffect(() => {
    if (!enabled || owner === null) return;
    let live = true;
    void offlineCache
      .get(kind, scope, entryKey)
      .then((hit) => {
        const data = hit ? fromCache(hit.data) : null;
        if (live && hit && data !== null) setSeed({ key: seedKey, cached: { data, fetchedAt: hit.fetchedAt, fromCache: true } });
      })
      .catch(() => {});
    return () => {
      live = false;
    };
    // `fromCache` is a pure function of its input: it is not a reason to read the cache again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, owner, kind, scope, entryKey, seedKey]);

  const current = query.data ?? (seed?.key === seedKey ? seed.cached : undefined);
  return {
    data: current?.data,
    fetchedAt: current?.fetchedAt,
    fromCache: current?.fromCache ?? false,
    isPending: current === undefined && query.isPending,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
    refetch: () => query.refetch(),
  };
}
