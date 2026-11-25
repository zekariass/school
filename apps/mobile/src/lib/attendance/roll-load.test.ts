import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';
import { rollEntryKey, rosterToCache, rollToCache } from '@/lib/offline/cache';
import type { CacheLike } from '@/lib/offline/cached-fetch';
import { CLASS, SCHOOL, u } from '@/lib/sync/test-support';

import { loadRoll, rollFromOfflineCache, type RollDeps } from './roll-load';
import type { RollSnapshot } from './roll-model';

const ZONE = 'Africa/Addis_Ababa';
// 2026-11-25 10:00 in Addis Ababa.
const NOW = Date.UTC(2026, 10, 25, 7, 0);

const server = (date: string, slot = 'DAY'): RollSnapshot => ({
  schoolId: SCHOOL,
  classId: CLASS,
  date,
  slot,
  slots: ['DAY', 'PM'],
  timeZone: ZONE,
  sessionVersion: 4,
  locked: false,
  locksAt: '2026-11-26T21:00:00Z',
  mayEditLocked: false,
  synthetic: false,
  students: [{ studentId: u(101), name: 'Abel', avatarMediaId: null, avatarColor: null, record: null, suggestedStatus: null, absence: null }],
});

function memory() {
  const rows = new Map<string, { data: unknown; fetchedAt: number }>();
  const forgotten: string[] = [];
  const key = (kind: string, scope: string, entry = '') => `${kind}|${scope}|${entry}`;
  const cache: CacheLike = {
    async get(kind, scope, entry) {
      return rows.get(key(kind, scope, entry)) ?? null;
    },
    async put(kind, scope, entry, data, fetchedAt = 0) {
      rows.set(key(kind, scope, entry), { data: JSON.parse(JSON.stringify(data)), fetchedAt });
    },
    async forgetClass(scope) {
      forgotten.push(scope);
      for (const k of [...rows.keys()]) if (k.split('|')[1] === scope) rows.delete(k);
    },
  };
  return { cache, rows, forgotten, key };
}

const request = { schoolId: SCHOOL, classId: CLASS, date: null, slot: null };

function deps(m: ReturnType<typeof memory>, fetchRoll: RollDeps['fetchRoll'], now = NOW): RollDeps {
  return { cache: m.cache, fetchRoll, now: () => now };
}

describe('loadRoll', () => {
  it('asks the server, and stores the roll under the date the server answered, plus the zone and slots', async () => {
    const m = memory();
    const result = await loadRoll(deps(m, async () => server('2026-11-25')), request);
    expect(result).toMatchObject({ fromCache: false, fetchedAt: NOW, data: { date: '2026-11-25' } });
    await Promise.resolve();
    expect(m.rows.has(m.key('roll', CLASS, rollEntryKey('2026-11-25', 'DAY')))).toBe(true);
    expect(m.rows.get(m.key('rollmeta', CLASS))?.data).toEqual({ timeZone: ZONE, slots: ['DAY', 'PM'] });
  });

  it('airplane mode: shows the cached roll of today with its age', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => server('2026-11-25')), request);
    const later = NOW + 2 * 3_600_000;
    const result = await loadRoll(deps(m, async () => { throw new ApiRequestError(undefined); }, later), request);
    expect(result).toMatchObject({ fromCache: true, fetchedAt: NOW, data: { date: '2026-11-25', sessionVersion: 4 } });
  });

  it('works out "today" from the school\'s zone, not the device\'s, when offline', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => server('2026-11-25')), request);
    // 2026-11-25 22:30 UTC is already the 26th in Addis Ababa: no roll for it was loaded, so it is built from the class list.
    await m.cache.put('roster', CLASS, '', rosterToCache([{ id: u(101), name: 'Abel', firstName: 'Abel', avatarMediaId: null, avatarConfig: null, accessLevel: 'FULL' }]), NOW);
    const result = await loadRoll(deps(m, async () => { throw new ApiRequestError(undefined); }, Date.UTC(2026, 10, 25, 22, 30)), request);
    expect(result.data).toMatchObject({ date: '2026-11-26', synthetic: true, sessionVersion: null });
  });

  it('builds today\'s roll from the class list when none was loaded, and only for today', async () => {
    const m = memory();
    await m.cache.put('rollmeta', CLASS, '', { timeZone: ZONE, slots: ['DAY'] }, NOW);
    await m.cache.put('roster', CLASS, '', rosterToCache([{ id: u(101), name: 'Abel Kebede', firstName: 'Abel', avatarMediaId: u(300), avatarConfig: null, accessLevel: 'BASIC' }]), NOW - 1000);
    const offline = async () => { throw new ApiRequestError(undefined); };
    const today = await loadRoll(deps(m, offline), request);
    expect(today).toMatchObject({ fromCache: true, fetchedAt: NOW - 1000 });
    expect(today.data).toMatchObject({ synthetic: true, date: '2026-11-25', slot: 'DAY', locked: false });
    expect(today.data.students).toEqual([{ studentId: u(101), name: 'Abel Kebede', avatarMediaId: u(300), avatarColor: null, record: null, suggestedStatus: null, absence: null }]);
    // Yesterday was never loaded: not invented.
    await expect(loadRoll(deps(m, offline), { ...request, date: '2026-11-24' })).rejects.toBeInstanceOf(ApiRequestError);
  });

  it('is unavailable when there is nothing to show (never opened this class)', async () => {
    const m = memory();
    await expect(loadRoll(deps(m, async () => { throw new ApiRequestError(undefined); }), request)).rejects.toBeInstanceOf(ApiRequestError);
  });

  it('returns another date or slot from the cache when it was loaded', async () => {
    const m = memory();
    await m.cache.put('rollmeta', CLASS, '', { timeZone: ZONE, slots: ['DAY', 'PM'] }, NOW);
    await m.cache.put('roll', CLASS, rollEntryKey('2026-11-24', 'PM'), rollToCache(server('2026-11-24', 'PM')), NOW - 86_400_000);
    const result = await rollFromOfflineCache({ cache: m.cache, now: () => NOW }, { ...request, date: '2026-11-24', slot: 'PM' });
    expect(result?.data).toMatchObject({ date: '2026-11-24', slot: 'PM' });
  });

  it('ignores a damaged cache entry', async () => {
    const m = memory();
    await m.cache.put('rollmeta', CLASS, '', { timeZone: ZONE, slots: ['DAY'] }, NOW);
    await m.cache.put('roll', CLASS, rollEntryKey('2026-11-25', 'DAY'), { students: 'x' }, NOW);
    expect(await rollFromOfflineCache({ cache: m.cache, now: () => NOW }, request)).toBeNull();
  });

  it('404 forgets the class: the teacher no longer teaches it', async () => {
    const m = memory();
    await m.cache.put('roster', CLASS, '', [], NOW);
    await expect(loadRoll(deps(m, async () => { throw new ApiRequestError(404); }), request)).rejects.toMatchObject({ status: 404 });
    expect(m.forgotten).toEqual([CLASS]);
  });

  it('403 (feature off, no right) is shown as it is and does not fall back to the cache', async () => {
    const m = memory();
    await loadRoll(deps(m, async () => server('2026-11-25')), request);
    await expect(loadRoll(deps(m, async () => { throw new ApiRequestError(403); }), request)).rejects.toMatchObject({ status: 403 });
    expect(m.forgotten).toEqual([]);
  });
});
