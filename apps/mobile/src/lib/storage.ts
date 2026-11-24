import Storage from 'expo-sqlite/kv-store';

/**
 * Async key-value store (AsyncStorage-compatible API) backed by SQLite. For
 * preferences and the query cache. Secrets belong in expo-secure-store instead.
 */
export const kv = Storage;

/** Best-effort write: a failed preference save must never break the UI. */
export function saveQuietly(key: string, value: string): void {
  kv.setItem(key, value).catch((error: unknown) => {
    if (__DEV__) console.warn(`[storage] could not save "${key}"`, error);
  });
}
