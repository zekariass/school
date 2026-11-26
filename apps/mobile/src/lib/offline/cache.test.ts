/// <reference types="node" />
import { DatabaseSync } from 'node:sqlite';

import { describe, expect, it } from 'vitest';

import { rollFromApi } from '@/lib/attendance/roll-model';
import { MIGRATIONS, runMigrations } from '@/lib/db/migrations';
import type { RosterEntry } from '@/lib/people/roster';
import { CLASS, SCHOOL, u } from '@/lib/sync/test-support';

import {
  cachedStudents,
  MAX_AGE_MS,
  OfflineCache,
  rollEntryKey,
  rollFromCache,
  rollMetaFromCache,
  rollToCache,
  ROLLS_PER_CLASS,
  rosterFromCache,
  rosterToCache,
  totalsFromCache,
  totalsToCache,
} from './cache';
import { sqlCacheStorage, type CacheDatabase } from './cache-sql';

const DAY = 24 * 60 * 60 * 1000;

async function setup(owner: string | null = 'teacher-1') {
  const raw = new DatabaseSync(':memory:');
  const db = {
    async runAsync(sql: string, params: (string | number | null)[]) {
      raw.prepare(sql).run(...params);
    },
    async getAllAsync<T>(sql: string, params: (string | number | null)[]) {
      return raw.prepare(sql).all(...params) as T[];
    },
    async execAsync(sql: string) {
      raw.exec(sql);
    },
    async getFirstAsync<T>(sql: string) {
      return (raw.prepare(sql).get() as T | undefined) ?? null;
    },
    async withTransactionAsync(task: () => Promise<void>) {
      raw.exec('BEGIN');
      await task();
      raw.exec('COMMIT');
    },
  } satisfies CacheDatabase & Record<string, unknown>;
  await runMigrations(db);
  const clock = { now: 1_000_000_000 };
  const state = { owner };
  const storage = sqlCacheStorage(async () => db);
  const cache = new OfflineCache(storage, () => state.owner, () => clock.now);
  return { raw, cache, clock, state, storage };
}

const roster: RosterEntry[] = [
  {
    id: u(101),
    name: 'Abel Kebede Tadesse',
    firstName: 'Abel',
    avatarMediaId: u(300),
    avatarConfig: { color: '#336699', mood: 'happy' },
    accessLevel: 'FULL',
  },
];

describe('what the roster keeps offline', () => {
  it('is id, display name, avatar media id and a placeholder colour: nothing private', () => {
    const cached = rosterToCache(roster);
    expect(cached).toEqual([{ id: u(101), name: 'Abel Kebede Tadesse', avatarMediaId: u(300), avatarColor: '#336699' }]);
    expect(Object.keys(cached[0]!)).toEqual(['id', 'name', 'avatarMediaId', 'avatarColor']);
  });
  it('comes back as roster entries without private access', () => {
    expect(rosterFromCache(rosterToCache(roster))).toEqual([
      { id: u(101), name: 'Abel Kebede Tadesse', firstName: 'Abel', avatarMediaId: u(300), avatarConfig: { color: '#336699' }, accessLevel: 'BASIC' },
    ]);
  });
  it('drops malformed entries and a colour that is not a colour', () => {
    expect(cachedStudents([{ id: 'x', name: 'a' }, { id: u(1), name: 'b', avatarColor: 'javascript:1' }, null])).toEqual([
      { id: u(1), name: 'b', avatarMediaId: null, avatarColor: null },
    ]);
    expect(cachedStudents('nope')).toEqual([]);
  });
});

describe('the roll in the cache', () => {
  const api = {
    classId: CLASS,
    date: '2026-11-25',
    slot: 'DAY',
    slots: ['DAY'],
    timeZone: 'Africa/Addis_Ababa',
    locksAt: '2026-11-26T21:00:00Z',
    sessionVersion: 3,
    students: [
      {
        studentId: u(101),
        displayName: 'Abel',
        avatar: { id: u(300), variants: [{ url: 'https://signed.example/x?sig=abc' }] },
        record: { status: 'ABSENT', note: 'fever', version: 1, recordedBy: u(5) },
        suggestedStatus: 'EXCUSED',
        absence: { reportId: u(500), status: 'ACKNOWLEDGED', reasonCode: 'ILLNESS', note: 'flu', fromDate: '2026-11-25', toDate: '2026-11-25' },
      },
    ],
  };
  const snapshot = rollFromApi(api as never, SCHOOL);

  it('round-trips through JSON', () => {
    expect(rollFromCache(JSON.parse(JSON.stringify(rollToCache(snapshot))))).toEqual(snapshot);
  });

  it('never holds a signed link, a teacher\'s note or a parent\'s words', () => {
    const text = JSON.stringify(rollToCache(snapshot));
    for (const forbidden of ['https://', 'sig=', 'fever', 'flu', 'variants', 'note']) expect(text).not.toContain(forbidden);
    // The reason is kept as a code, so "parent reported illness" still works offline.
    expect(text).toContain('ILLNESS');
  });

  it('a synthetic roll is never stored as if the server had said it', () => {
    expect(rollToCache({ ...snapshot, synthetic: true }).synthetic).toBe(false);
  });

  it('rejects a damaged entry as a whole', () => {
    expect(rollFromCache({ ...rollToCache(snapshot), students: [{ studentId: 'x' }] })).toBeNull();
    expect(rollFromCache(null)).toBeNull();
    expect(rollFromCache({ classId: CLASS })).toBeNull();
  });

  it('reads the zone and slots of a class', () => {
    expect(rollMetaFromCache({ timeZone: 'Africa/Addis_Ababa', slots: ['DAY', 'PM'] })).toEqual({ timeZone: 'Africa/Addis_Ababa', slots: ['DAY', 'PM'] });
    expect(rollMetaFromCache({ timeZone: 'x', slots: [] })).toBeNull();
  });
});

describe('totals', () => {
  it('are numbers per student id', () => {
    expect(totalsToCache([{ studentId: u(1), total: 5 }, { studentId: u(2) }])).toEqual({ [u(1)]: 5, [u(2)]: 0 });
    expect(totalsFromCache({ [u(1)]: 5, [u(2)]: 'x' })).toEqual({ [u(1)]: 5 });
  });
});

describe('OfflineCache', () => {
  it('stores and returns an entry for the signed-in user only', async () => {
    const t = await setup('teacher-1');
    await t.cache.put('roster', CLASS, '', rosterToCache(roster));
    expect((await t.cache.get('roster', CLASS))?.data).toEqual(rosterToCache(roster));
    t.state.owner = 'teacher-2';
    expect(await t.cache.get('roster', CLASS)).toBeNull();
    t.state.owner = null;
    expect(await t.cache.get('roster', CLASS)).toBeNull();
    await t.cache.put('roster', CLASS, '', []);
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache').get()).toEqual({ n: 1 });
  });

  it('does not return, and deletes, an entry older than the maximum age of its kind', async () => {
    const t = await setup();
    await t.cache.put('roll', CLASS, rollEntryKey('2026-11-25', 'DAY'), {}, t.clock.now);
    await t.cache.put('totals', CLASS, '', {}, t.clock.now);
    t.clock.now += MAX_AGE_MS.totals + 1;
    expect(await t.cache.get('totals', CLASS)).toBeNull();
    expect(await t.cache.get('roll', CLASS, rollEntryKey('2026-11-25', 'DAY'))).not.toBeNull();
    t.clock.now += MAX_AGE_MS.roll;
    expect(await t.cache.get('roll', CLASS, rollEntryKey('2026-11-25', 'DAY'))).toBeNull();
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache').get()).toEqual({ n: 0 });
  });

  it('keeps only the newest rolls of a class', async () => {
    const t = await setup();
    for (let day = 1; day <= ROLLS_PER_CLASS + 3; day++) {
      t.clock.now += 1000;
      await t.cache.put('roll', CLASS, rollEntryKey(`2026-11-${String(day).padStart(2, '0')}`, 'DAY'), {}, t.clock.now);
    }
    const rows = t.raw.prepare("SELECT entry_key FROM offline_cache WHERE kind = 'roll'").all() as { entry_key: string }[];
    expect(rows).toHaveLength(ROLLS_PER_CLASS);
    expect(rows.map((row) => row.entry_key)).not.toContain('2026-11-01|DAY');
    expect(rows.map((row) => row.entry_key)).toContain(`2026-11-${ROLLS_PER_CLASS + 3}|DAY`);
  });

  it('purges the classes a teacher no longer teaches, after a successful refresh of their class list', async () => {
    const t = await setup();
    const kept = u(901);
    const gone = u(902);
    for (const scope of [kept, gone]) {
      for (const kind of ['roster', 'roll', 'rollmeta', 'categories', 'totals', 'templates'] as const) await t.cache.put(kind, scope, '', {});
    }
    await t.cache.put('classes', '', '', [{ id: kept }, { id: gone }]);
    await t.cache.purgeClassesExcept([kept]);
    const scopes = (t.raw.prepare('SELECT DISTINCT scope FROM offline_cache').all() as { scope: string }[]).map((row) => row.scope).sort();
    expect(scopes).toEqual(['', kept].sort());
    // Every kind of the kept class stays, and the class list itself.
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache WHERE scope = ?').get(kept)).toEqual({ n: 6 });
    expect(await t.cache.get('classes', '')).not.toBeNull();
    await t.cache.purgeClassesExcept([]);
    expect(t.raw.prepare("SELECT COUNT(*) AS n FROM offline_cache WHERE scope <> ''").get()).toEqual({ n: 0 });
  });

  it('forgets a whole class when the API says it is gone (403/404)', async () => {
    const t = await setup();
    await t.cache.put('roster', CLASS, '', []);
    await t.cache.put('roll', CLASS, 'k', {});
    await t.cache.put('roster', u(902), '', []);
    await t.cache.forgetClass(CLASS);
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache').get()).toEqual({ n: 1 });
  });

  it('tidy() removes other users\' entries and anything past its age', async () => {
    const t = await setup('teacher-1');
    await t.cache.put('roster', CLASS, '', []);
    await t.cache.put('totals', CLASS, '', {}, t.clock.now - MAX_AGE_MS.totals - DAY);
    t.state.owner = 'teacher-2';
    await t.cache.put('roster', CLASS, '', []);
    t.state.owner = 'teacher-1';
    await t.cache.tidy();
    const rows = t.raw.prepare('SELECT owner_id, kind FROM offline_cache').all();
    expect(rows).toEqual([{ owner_id: 'teacher-1', kind: 'roster' }]);
  });

  it('clear() wipes everything: sign-out and the end of a session leave nothing behind', async () => {
    const t = await setup();
    await t.cache.put('roster', CLASS, '', rosterToCache(roster));
    await t.cache.put('classes', '', '', []);
    await t.cache.clear();
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache').get()).toEqual({ n: 0 });
  });

  it('survives a damaged entry', async () => {
    const t = await setup();
    t.raw.prepare("INSERT INTO offline_cache VALUES ('teacher-1', 'roster', ?, '', '{not json', ?)").run(CLASS, t.clock.now);
    expect(await t.cache.get('roster', CLASS)).toBeNull();
    expect(t.raw.prepare('SELECT COUNT(*) AS n FROM offline_cache').get()).toEqual({ n: 0 });
  });

  it('is migration 4, holds data per user and has no column for signed links', async () => {
    expect(MIGRATIONS[3]?.name).toBe('create_offline_cache');
    const t = await setup();
    const columns = (t.raw.prepare('PRAGMA table_info(offline_cache)').all() as { name: string }[]).map((c) => c.name);
    expect(columns).toEqual(['owner_id', 'kind', 'scope', 'entry_key', 'data', 'fetched_at']);
  });
});

describe('what stays out of the cache', () => {
  it('has no kind for a student record, a login code, a note or a signed media link', () => {
    expect(Object.keys(MAX_AGE_MS).sort()).toEqual(['categories', 'classes', 'roll', 'rollmeta', 'roster', 'templates', 'totals']);
  });
});

