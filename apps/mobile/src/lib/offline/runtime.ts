import { useSyncExternalStore } from 'react';

import { getDatabase } from '@/lib/db/database';

import { OfflineCache } from './cache';
import { sqlCacheStorage } from './cache-sql';

/**
 * The device's offline read cache, bound to the signed-in teacher. Nothing is read or written until an owner is
 * set (teachers only: the cache exists for the class screens and the roll), and every wipe clears it.
 */

let ownerId: string | null = null;
const listeners = new Set<() => void>();

export const offlineCache = new OfflineCache(
  sqlCacheStorage(getDatabase),
  () => ownerId,
  () => Date.now(),
);

export function setOfflineOwner(next: string | null): void {
  if (next === ownerId) return;
  ownerId = next;
  for (const listener of listeners) listener();
}

/** The user whose cache is in use (null: caching is off). Re-renders when it changes. */
export function useOfflineOwner(): string | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => ownerId,
    () => ownerId,
  );
}
