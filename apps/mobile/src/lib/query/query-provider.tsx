import { defaultShouldDehydrateQuery } from '@tanstack/react-query';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import Constants from 'expo-constants';
import type { ReactNode } from 'react';

import { isPersistableAttendanceQuery } from '@/lib/attendance/keys';
import { isPersistableFamilyQuery } from '@/lib/family/keys';
import { isPersistableFeedQuery } from '@/lib/feed/keys';
import { shouldPersistQuery } from '@/lib/media/url-cache';
import { isPersistableNotificationQuery } from '@/lib/notifications/keys';
import { isPersistablePeopleQuery } from '@/lib/people/keys';
import { isPersistablePointsQuery } from '@/lib/points/keys';

import { QUERY_CACHE_MAX_AGE_MS, queryClient, queryPersister } from './query-client';

/**
 * TanStack Query with the cache persisted to SQLite (kv-store) for 24 h, so lists open
 * instantly and stay readable offline. The app version busts the cache on upgrade.
 */
export function AppQueryProvider({ children }: { children: ReactNode }) {
  return (
    <PersistQueryClientProvider
      client={queryClient}
      persistOptions={{
        persister: queryPersister,
        maxAge: QUERY_CACHE_MAX_AGE_MS,
        // The shape of the class and roster queries changed with the offline cache (M7b): older persisted copies are dropped.
        buster: `${Constants.expoConfig?.version ?? '0'}+offline1`,
        // Signed media links are dead after a restart, and notification texts and counts are cheap to
        // refetch and describe children's school life: keep both out of the persisted cache. So are
        // student records (date of birth...) and school-wide student searches, and the guardian screens
        // (children, consent), children's points, timelines and badges, rosters and everything about attendance and
        // absence reports, and the whole feed (posts, comments, stories, tagged children: memory only, M8c). What a teacher needs offline (roster, roll, skills, totals, templates) is stored by
        // `lib/offline` in its own owner-scoped table; login codes and parent codes never enter a query at all.
        dehydrateOptions: {
          shouldDehydrateQuery: (query) =>
            defaultShouldDehydrateQuery(query) &&
            shouldPersistQuery(query.queryKey) &&
            isPersistableNotificationQuery(query.queryKey) &&
            isPersistablePeopleQuery(query.queryKey) &&
            isPersistableFamilyQuery(query.queryKey) &&
            isPersistableFeedQuery(query.queryKey) &&
            isPersistablePointsQuery(query.queryKey) &&
            isPersistableAttendanceQuery(query.queryKey),
        },
      }}>
      {children}
    </PersistQueryClientProvider>
  );
}
