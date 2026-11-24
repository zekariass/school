import { describe, expect, it } from 'vitest';

import {
  MIGRATIONS,
  pendingMigrations,
  runMigrations,
  validateMigrations,
  type Migration,
  type MigrationDatabase,
} from './migrations';

const m = (version: number, statements: string[] = ['SELECT 1']): Migration => ({
  version,
  name: `m${version}`,
  statements,
});

function fakeDb(startVersion: number) {
  const log: string[] = [];
  let version = startVersion;
  const db: MigrationDatabase = {
    async getFirstAsync<T>() {
      return { user_version: version } as T;
    },
    async execAsync(sql) {
      log.push(sql);
      const match = /^PRAGMA user_version = (\d+)$/.exec(sql);
      if (match) version = Number(match[1]);
    },
    async withTransactionAsync(task) {
      log.push('BEGIN');
      await task();
      log.push('COMMIT');
    },
  };
  return { db, log, version: () => version };
}

describe('shipped migrations', () => {
  it('are valid and create the sync outbox', () => {
    expect(() => validateMigrations(MIGRATIONS)).not.toThrow();
    const sql = MIGRATIONS[0]?.statements.join('\n') ?? '';
    expect(sql).toContain('CREATE TABLE sync_outbox');
    expect(sql).toContain('client_operation_id TEXT NOT NULL UNIQUE');
    expect(sql).toContain("status TEXT NOT NULL DEFAULT 'PENDING'");
  });
});

describe('validateMigrations', () => {
  it('requires consecutive versions starting at 1', () => {
    expect(() => validateMigrations([m(1), m(2), m(3)])).not.toThrow();
    expect(() => validateMigrations([m(2)])).toThrow(/expected 1/);
    expect(() => validateMigrations([m(1), m(3)])).toThrow(/expected 2/);
    expect(() => validateMigrations([m(1), m(1)])).toThrow(/expected 2/);
  });

  it('requires statements', () => {
    expect(() => validateMigrations([m(1, [])])).toThrow(/no SQL statements/);
    expect(() => validateMigrations([m(1, ['  '])])).toThrow(/no SQL statements/);
  });
});

describe('pendingMigrations', () => {
  it('returns only the migrations after the current version, in order', () => {
    const all = [m(1), m(2), m(3)];
    expect(pendingMigrations(all, 0).map((x) => x.version)).toEqual([1, 2, 3]);
    expect(pendingMigrations(all, 2).map((x) => x.version)).toEqual([3]);
    expect(pendingMigrations(all, 3)).toEqual([]);
  });

  it('refuses a database that is newer than the app', () => {
    expect(() => pendingMigrations([m(1)], 2)).toThrow(/newer than this app/);
    expect(() => pendingMigrations([m(1)], -1)).toThrow(/Invalid database version/);
  });
});

describe('runMigrations', () => {
  it('applies each pending migration in its own transaction and bumps the version', async () => {
    const { db, log, version } = fakeDb(0);
    const final = await runMigrations(db, [m(1, ['A']), m(2, ['B', 'C'])]);
    expect(final).toBe(2);
    expect(version()).toBe(2);
    expect(log).toEqual([
      'BEGIN', 'A', 'PRAGMA user_version = 1', 'COMMIT',
      'BEGIN', 'B', 'C', 'PRAGMA user_version = 2', 'COMMIT',
    ]);
  });

  it('is a no-op when up to date and resumes from a partial state', async () => {
    const upToDate = fakeDb(2);
    await runMigrations(upToDate.db, [m(1), m(2)]);
    expect(upToDate.log).toEqual([]);

    const partial = fakeDb(1);
    await runMigrations(partial.db, [m(1, ['A']), m(2, ['B'])]);
    expect(partial.log).toEqual(['BEGIN', 'B', 'PRAGMA user_version = 2', 'COMMIT']);
  });
});
