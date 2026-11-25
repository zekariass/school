import type { CacheKind, CacheRow, CacheStorage } from './cache';

/** The subset of expo-sqlite's database the cache needs (so a test can back it with another SQLite). */
export interface CacheDatabase {
  runAsync(sql: string, params: (string | number | null)[]): Promise<unknown>;
  getAllAsync<T>(sql: string, params: (string | number | null)[]): Promise<T[]>;
}

/** Migration 4 of the local database: the offline read cache (see `cache.ts` for what may be stored). */
export const OFFLINE_CACHE_STATEMENTS: readonly string[] = [
  `CREATE TABLE offline_cache (
    owner_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    scope TEXT NOT NULL,
    entry_key TEXT NOT NULL,
    data TEXT NOT NULL,
    fetched_at INTEGER NOT NULL,
    PRIMARY KEY (owner_id, kind, scope, entry_key)
  )`,
  'CREATE INDEX idx_offline_cache_scope ON offline_cache (owner_id, scope)',
];

interface Row {
  owner_id: string;
  kind: string;
  scope: string;
  entry_key: string;
  data: string;
  fetched_at: number;
}

export function sqlCacheStorage(db: () => Promise<CacheDatabase>): CacheStorage {
  return {
    async get(ownerId, kind, scope, entryKey) {
      const rows = await (
        await db()
      ).getAllAsync<Row>('SELECT * FROM offline_cache WHERE owner_id = ? AND kind = ? AND scope = ? AND entry_key = ?', [ownerId, kind, scope, entryKey]);
      const row = rows[0];
      return row ? { ownerId: row.owner_id, kind: row.kind as CacheKind, scope: row.scope, entryKey: row.entry_key, data: row.data, fetchedAt: row.fetched_at } : null;
    },
    async put(row: CacheRow) {
      await (
        await db()
      ).runAsync(
        `INSERT INTO offline_cache (owner_id, kind, scope, entry_key, data, fetched_at) VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT (owner_id, kind, scope, entry_key) DO UPDATE SET data = excluded.data, fetched_at = excluded.fetched_at`,
        [row.ownerId, row.kind, row.scope, row.entryKey, row.data, row.fetchedAt],
      );
    },
    async delete(ownerId, kind, scope, entryKey) {
      await (await db()).runAsync('DELETE FROM offline_cache WHERE owner_id = ? AND kind = ? AND scope = ? AND entry_key = ?', [ownerId, kind, scope, entryKey]);
    },
    async deleteScope(ownerId, scope) {
      await (await db()).runAsync("DELETE FROM offline_cache WHERE owner_id = ? AND scope = ? AND scope <> ''", [ownerId, scope]);
    },
    async deleteScopesExcept(ownerId, keep) {
      const marks = keep.map(() => '?').join(', ');
      await (
        await db()
      ).runAsync(`DELETE FROM offline_cache WHERE owner_id = ? AND scope <> ''${keep.length > 0 ? ` AND scope NOT IN (${marks})` : ''}`, [ownerId, ...keep]);
    },
    async deleteOlderThan(kind, cutoff) {
      await (await db()).runAsync('DELETE FROM offline_cache WHERE kind = ? AND fetched_at < ?', [kind, cutoff]);
    },
    async keepNewest(ownerId, kind, scope, count) {
      await (
        await db()
      ).runAsync(
        `DELETE FROM offline_cache
          WHERE owner_id = ? AND kind = ? AND scope = ?
            AND entry_key NOT IN (
              SELECT entry_key FROM offline_cache WHERE owner_id = ? AND kind = ? AND scope = ? ORDER BY fetched_at DESC LIMIT ?
            )`,
        [ownerId, kind, scope, ownerId, kind, scope, count],
      );
    },
    async scopes(ownerId) {
      const rows = await (await db()).getAllAsync<{ scope: string }>("SELECT DISTINCT scope FROM offline_cache WHERE owner_id = ? AND scope <> ''", [ownerId]);
      return rows.map((row) => row.scope);
    },
    async deleteOtherOwners(ownerId) {
      await (await db()).runAsync('DELETE FROM offline_cache WHERE owner_id <> ?', [ownerId]);
    },
    async clear() {
      await (await db()).runAsync('DELETE FROM offline_cache', []);
    },
  };
}
