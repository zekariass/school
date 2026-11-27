import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { offlineCache, useOfflineOwner } from '@/lib/offline/runtime';
import type { Cached } from '@/lib/offline/cached-fetch';

import { rollKey } from './keys';
import { loadRoll, rollFromOfflineCache, type LoadedRoll } from './roll-load';
import { rollFromApi, type RollSnapshot } from './roll-model';

export interface UseRollInput {
  schoolId: string | undefined;
  classId: string | undefined;
  /** Null: the school's today. */
  date: string | null;
  /** Null: the school's first slot. */
  slot: string | null;
  /** Null: the whole class; else a saved group (an elective's roster). */
  groupId?: string | null;
}

export interface UseRollResult {
  roll: RollSnapshot | undefined;
  /** When the server last confirmed this roll (the class list's time for a roll built from it). */
  fetchedAt: number | undefined;
  /** The server could not be asked: this is the offline copy. */
  fromCache: boolean;
  isPending: boolean;
  isFetching: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => Promise<unknown>;
}

/**
 * The roll of a class on a date and slot: from the server when it can be asked, else the offline copy, else (for
 * today) the class list. The cached copy is shown at once while the server is being asked. Never persisted by
 * TanStack: the offline copy is the owner-scoped table of `lib/offline`.
 */
export function useRoll({ schoolId, classId, date, slot, groupId = null }: UseRollInput): UseRollResult {
  const owner = useOfflineOwner();
  const enabled = !!schoolId && !!classId;

  const query = useQuery<LoadedRoll>({
    queryKey: rollKey(classId ?? '', date, slot, groupId),
    enabled,
    staleTime: (q) => (q.state.data?.fromCache ? 0 : 30_000),
    queryFn: () =>
      loadRoll(
        {
          cache: offlineCache,
          now: () => Date.now(),
          fetchRoll: (wantedDate, wantedSlot, wantedGroup) =>
            request(async () =>
              rollFromApi(
                unwrap(
                  await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/attendance', {
                    params: {
                      path: { schoolId: schoolId!, classId: classId! },
                      query: {
                        ...(wantedDate ? { date: wantedDate } : {}),
                        ...(wantedSlot ? { slot: wantedSlot } : {}),
                        ...(wantedGroup ? { groupId: wantedGroup } : {}),
                      },
                    },
                  }),
                ),
                schoolId!,
              ),
            ),
        },
        { schoolId: schoolId!, classId: classId!, date, slot, groupId },
      ),
  });

  // Something to show while the server is being asked (or cannot be).
  const seedKey = `${classId}|${date ?? 'today'}|${slot ?? 'first'}|${groupId ?? 'class'}`;
  const [seed, setSeed] = useState<{ key: string; cached: Cached<RollSnapshot> } | null>(null);
  useEffect(() => {
    if (!enabled || owner === null) return;
    let live = true;
    void rollFromOfflineCache({ cache: offlineCache, now: () => Date.now() }, { schoolId: schoolId!, classId: classId!, date, slot, groupId })
      .then((loaded) => {
        if (live && loaded) setSeed({ key: seedKey, cached: loaded });
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [enabled, owner, schoolId, classId, date, slot, groupId, seedKey]);

  const current = query.data ?? (seed?.key === seedKey ? seed.cached : undefined);
  return {
    roll: current?.data,
    fetchedAt: current?.fetchedAt,
    fromCache: current?.fromCache ?? false,
    isPending: current === undefined && query.isPending,
    isFetching: query.isFetching,
    isError: query.isError,
    error: query.error,
    refetch: () => query.refetch(),
  };
}
