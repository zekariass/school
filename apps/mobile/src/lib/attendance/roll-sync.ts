import { queryClient } from '@/lib/query/query-client';
import { offlineCache } from '@/lib/offline/runtime';
import { rollEntryKey, rollFromCache, rollToCache } from '@/lib/offline/cache';
import type { Cached } from '@/lib/offline/cached-fetch';
import type { QueuedAttendance } from '@/lib/sync/types';

import { classRollsKey } from './keys';
import { applyServerState, type RollSnapshot } from './roll-model';

/**
 * After the server answered an ATTENDANCE_MARK (applied, or a conflict where it kept a colleague's mark): the roll
 * on the screen and the offline copy take over what the server stored, so nothing falls back to old values before
 * the next fetch. The screen then asks the server for the whole roll in the background.
 */
export async function patchRollFromResult(item: QueuedAttendance, state: unknown): Promise<void> {
  const { classId } = item;
  const { date, slot } = item.payload;

  queryClient.setQueriesData<Cached<RollSnapshot>>({ queryKey: classRollsKey(classId) }, (old) =>
    old && old.data.date === date && old.data.slot === slot ? { ...old, data: applyServerState(old.data, state) } : old,
  );

  try {
    const hit = await offlineCache.get('roll', classId, rollEntryKey(date, slot));
    const roll = hit ? rollFromCache(hit.data) : null;
    // The entry keeps its own age: only the marks this device wrote are newer than it.
    if (hit && roll) await offlineCache.put('roll', classId, rollEntryKey(date, slot), rollToCache(applyServerState(roll, state)), hit.fetchedAt);
  } catch {
    // Best effort: the next fetch rewrites the entry.
  }

  void queryClient.invalidateQueries({ queryKey: classRollsKey(classId) });
}
