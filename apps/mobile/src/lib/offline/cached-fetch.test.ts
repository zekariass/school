import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import type { CacheHit, CacheKind } from './cache';
import { fetchThroughCache, isUnreachable, type CacheLike } from './cached-fetch';

function fakeCache(initial?: { data: unknown; fetchedAt: number }) {
  const puts: unknown[][] = [];
  const forgotten: string[] = [];
  let entry = initial ?? null;
  const cache: CacheLike = {
    async get(_kind: CacheKind, _scope: string, _key?: string): Promise<CacheHit<unknown> | null> {
      return entry;
    },
    async put(kind, scope, key, data, fetchedAt) {
      puts.push([kind, scope, key, data, fetchedAt]);
      entry = { data, fetchedAt: fetchedAt ?? 0 };
    },
    async forgetClass(scope) {
      forgotten.push(scope);
      entry = null;
    },
  };
  return { cache, puts, forgotten };
}

const spec = (fetch: () => Promise<string[]>) => ({
  kind: 'roster' as const,
  scope: 'class-1',
  fetch,
  toCache: (data: string[]) => data.map((name) => ({ name })),
  fromCache: (stored: unknown) => (Array.isArray(stored) ? (stored as { name: string }[]).map((item) => item.name) : null),
});

describe('fetchThroughCache', () => {
  it('returns the fresh answer and writes only what toCache allows', async () => {
    const t = fakeCache();
    const result = await fetchThroughCache(t.cache, spec(async () => ['Abel']), () => 5000);
    expect(result).toEqual({ data: ['Abel'], fetchedAt: 5000, fromCache: false });
    expect(t.puts).toEqual([['roster', 'class-1', '', [{ name: 'Abel' }], 5000]]);
  });

  it('does not fail when the cache cannot be written', async () => {
    const t = fakeCache();
    t.cache.put = async () => {
      throw new Error('disk full');
    };
    await expect(fetchThroughCache(t.cache, spec(async () => ['Abel']))).resolves.toMatchObject({ fromCache: false });
  });

  it.each([undefined, 500, 503, 429, 408, 401])('answers from the cache when the server cannot be asked (%s)', async (status) => {
    const t = fakeCache({ data: [{ name: 'Cached' }], fetchedAt: 777 });
    const result = await fetchThroughCache(
      t.cache,
      spec(async () => {
        throw new ApiRequestError(status);
      }),
    );
    expect(result).toEqual({ data: ['Cached'], fetchedAt: 777, fromCache: true });
    expect(t.forgotten).toEqual([]);
  });

  it('raises the error when there is nothing usable in the cache', async () => {
    const t = fakeCache();
    await expect(fetchThroughCache(t.cache, spec(async () => { throw new ApiRequestError(undefined); }))).rejects.toBeInstanceOf(ApiRequestError);
    const bad = fakeCache({ data: 'garbage', fetchedAt: 1 });
    await expect(fetchThroughCache(bad.cache, spec(async () => { throw new ApiRequestError(500); }))).rejects.toBeInstanceOf(ApiRequestError);
  });

  it('404 forgets the class and raises: the teacher no longer teaches it', async () => {
    const t = fakeCache({ data: [{ name: 'Cached' }], fetchedAt: 1 });
    await expect(fetchThroughCache(t.cache, spec(async () => { throw new ApiRequestError(404); }))).rejects.toMatchObject({ status: 404 });
    expect(t.forgotten).toEqual(['class-1']);
  });

  it('403 and other answers are raised without touching the cache (a disabled feature also says 403)', async () => {
    for (const status of [400, 403, 409, 422]) {
      const t = fakeCache({ data: [{ name: 'Cached' }], fetchedAt: 1 });
      await expect(fetchThroughCache(t.cache, spec(async () => { throw new ApiRequestError(status); }))).rejects.toMatchObject({ status });
      expect(t.forgotten).toEqual([]);
    }
  });

  it('knows which statuses mean "could not ask"', () => {
    expect([undefined, 502, 429, 401].every(isUnreachable)).toBe(true);
    expect([400, 403, 404, 409].some(isUnreachable)).toBe(false);
  });
});
