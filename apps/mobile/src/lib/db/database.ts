import { openDatabaseAsync, type SQLiteDatabase } from 'expo-sqlite';

import { runMigrations } from './migrations';

const DATABASE_NAME = 'school.db';

let opening: Promise<SQLiteDatabase> | undefined;

/** The app database, opened once and migrated to the latest schema. */
export function getDatabase(): Promise<SQLiteDatabase> {
  opening ??= (async () => {
    const db = await openDatabaseAsync(DATABASE_NAME);
    await db.execAsync('PRAGMA journal_mode = WAL');
    await runMigrations(db);
    return db;
  })();
  // Allow a retry after a failure.
  opening.catch(() => {
    opening = undefined;
  });
  return opening;
}
