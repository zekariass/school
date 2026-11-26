import {
  cachedStudents,
  rollEntryKey,
  rollFromCache,
  rollMetaFromCache,
  rollToCache,
  type RollMeta,
} from '@/lib/offline/cache';
import { isUnreachable, statusOf, type Cached, type CacheLike } from '@/lib/offline/cached-fetch';

import { localDateIn } from './dates';
import { syntheticRoll, type RollSnapshot } from './roll-model';

/**
 * Where a roll comes from: the server when it can be asked, otherwise the offline cache, otherwise (for today only)
 * the class list with nobody marked. Either way the answer says how old it is and whether it came from the cache.
 */

export interface RollDeps {
  readonly cache: CacheLike;
  /** `GET .../attendance`: with no date and slot the server answers its own today and the first slot. */
  readonly fetchRoll: (date: string | null, slot: string | null) => Promise<RollSnapshot>;
  readonly now: () => number;
}

export interface RollRequest {
  readonly schoolId: string;
  readonly classId: string;
  /** Null: the school's today. */
  readonly date: string | null;
  readonly slot: string | null;
}

export type LoadedRoll = Cached<RollSnapshot>;

export async function cachedRollMeta(cache: CacheLike, classId: string): Promise<RollMeta | null> {
  const hit = await cache.get('rollmeta', classId, '');
  return hit ? rollMetaFromCache(hit.data) : null;
}

/** The roll as far as the cache can tell, without asking the server (null: nothing usable). */
export async function rollFromOfflineCache(deps: Pick<RollDeps, 'cache' | 'now'>, request: RollRequest): Promise<LoadedRoll | null> {
  const meta = await cachedRollMeta(deps.cache, request.classId);
  const slot = request.slot ?? meta?.slots[0] ?? 'DAY';
  const today = meta ? localDateIn(meta.timeZone, deps.now()) : null;
  const date = request.date ?? today;
  if (date === null) return null;

  const hit = await deps.cache.get('roll', request.classId, rollEntryKey(date, slot));
  const cached = hit ? rollFromCache(hit.data) : null;
  if (hit && cached) return { data: cached, fetchedAt: hit.fetchedAt, fromCache: true };

  // No roll was loaded for today yet: the class list is enough to take it (the server settles clashes later).
  if (meta && date === today && (request.slot === null || meta.slots.includes(slot))) {
    const rosterHit = await deps.cache.get('roster', request.classId, '');
    const roster = rosterHit ? cachedStudents(rosterHit.data) : [];
    if (rosterHit && roster.length > 0) {
      return {
        data: syntheticRoll({
          schoolId: request.schoolId,
          classId: request.classId,
          date,
          slot,
          slots: meta.slots,
          timeZone: meta.timeZone,
          roster,
        }),
        fetchedAt: rosterHit.fetchedAt,
        fromCache: true,
      };
    }
  }
  return null;
}

export async function loadRoll(deps: RollDeps, request: RollRequest): Promise<LoadedRoll> {
  try {
    const roll = await deps.fetchRoll(request.date, request.slot);
    const fetchedAt = deps.now();
    // Best effort: a failed write only means no offline copy of this roll.
    void deps.cache.put('roll', request.classId, rollEntryKey(roll.date, roll.slot), rollToCache(roll), fetchedAt).catch(() => {});
    void deps.cache.put('rollmeta', request.classId, '', { timeZone: roll.timeZone, slots: roll.slots }, fetchedAt).catch(() => {});
    return { data: roll, fetchedAt, fromCache: false };
  } catch (error) {
    const status = statusOf(error);
    if (isUnreachable(status)) {
      const offline = await rollFromOfflineCache(deps, request).catch(() => null);
      if (offline) return offline;
    } else if (status === 404) {
      void deps.cache.forgetClass(request.classId).catch(() => {});
    }
    throw error;
  }
}
