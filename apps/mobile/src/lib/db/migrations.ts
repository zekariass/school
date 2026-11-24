/**
 * Local SQLite schema versioning. `PRAGMA user_version` holds the number of the last
 * applied migration; migrations are an ordered, append-only list (never edit or
 * reorder a shipped one, add a new one).
 */
export interface Migration {
  /** 1-based, consecutive. */
  version: number;
  name: string;
  /** SQL statements run in one transaction together with the version bump. */
  statements: readonly string[];
}

/**
 * Mirrors the backend's planned `sync_operations` ledger (docs/Database_Design.md
 * §4.6): the server de-duplicates by (device id, client_operation_id), so retries of
 * an outbox row are idempotent.
 */
export const MIGRATIONS: readonly Migration[] = [
  {
    version: 1,
    name: 'create_sync_outbox',
    statements: [
      `CREATE TABLE sync_outbox (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        client_operation_id TEXT NOT NULL UNIQUE,
        operation_type TEXT NOT NULL,
        entity_type TEXT NOT NULL,
        entity_id TEXT,
        base_version INTEGER,
        payload TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        attempts INTEGER NOT NULL DEFAULT 0,
        last_error TEXT,
        created_at TEXT NOT NULL
      )`,
      'CREATE INDEX idx_sync_outbox_status ON sync_outbox (status, id)',
    ],
  },
];

/** Throws unless versions are 1, 2, 3, … with a name and at least one statement each. */
export function validateMigrations(migrations: readonly Migration[]): void {
  migrations.forEach((migration, index) => {
    const expected = index + 1;
    if (migration.version !== expected) {
      throw new Error(
        `Migration "${migration.name}" has version ${migration.version}, expected ${expected} (versions must be consecutive from 1).`,
      );
    }
    if (!migration.name.trim()) throw new Error(`Migration ${migration.version} has no name.`);
    if (migration.statements.length === 0 || migration.statements.some((s) => !s.trim())) {
      throw new Error(`Migration ${migration.version} ("${migration.name}") has no SQL statements.`);
    }
  });
}

/** Migrations newer than `currentVersion`, in order. Throws when the database is ahead of the app. */
export function pendingMigrations(migrations: readonly Migration[], currentVersion: number): Migration[] {
  validateMigrations(migrations);
  const latest = migrations.length;
  if (!Number.isInteger(currentVersion) || currentVersion < 0) {
    throw new Error(`Invalid database version ${currentVersion}.`);
  }
  if (currentVersion > latest) {
    throw new Error(
      `Database version ${currentVersion} is newer than this app understands (${latest}). Update the app.`,
    );
  }
  return migrations.slice(currentVersion);
}

/** The subset of expo-sqlite's SQLiteDatabase the runner needs (so it can be faked in tests). */
export interface MigrationDatabase {
  execAsync(sql: string): Promise<void>;
  getFirstAsync<T>(sql: string): Promise<T | null>;
  withTransactionAsync(task: () => Promise<void>): Promise<void>;
}

/** Applies pending migrations, each atomically with its version bump. Returns the final version. */
export async function runMigrations(
  db: MigrationDatabase,
  migrations: readonly Migration[] = MIGRATIONS,
): Promise<number> {
  const row = await db.getFirstAsync<{ user_version: number }>('PRAGMA user_version');
  const pending = pendingMigrations(migrations, row?.user_version ?? 0);
  for (const migration of pending) {
    await db.withTransactionAsync(async () => {
      for (const statement of migration.statements) await db.execAsync(statement);
      // PRAGMA cannot take bound parameters; the version is a validated integer.
      await db.execAsync(`PRAGMA user_version = ${migration.version}`);
    });
  }
  return migrations.length;
}
