import { defaultShouldDehydrateQuery } from '@tanstack/react-query';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import Constants from 'expo-constants';
import type { ReactNode } from 'react';

import { isPersistableFamilyQuery } from '@/lib/family/keys';
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
        buster: Constants.expoConfig?.version ?? '0',
        // Signed media links are dead after a restart, and notification texts and counts are cheap to
        // refetch and describe children's school life: keep both out of the persisted cache. So are
        // student records (date of birth...) and school-wide student searches, and the guardian screens
        // (children, consent) and children's points, timelines and badges (only the skill palettes and routine
        // templates are kept, so a teacher can award offline); login codes and parent codes never enter a query at all.
        dehydrateOptions: {
          shouldDehydrateQuery: (query) =>
            defaultShouldDehydrateQuery(query) &&
            shouldPersistQuery(query.queryKey) &&
            isPersistableNotificationQuery(query.queryKey) &&
            isPersistablePeopleQuery(query.queryKey) &&
            isPersistableFamilyQuery(query.queryKey) &&
            isPersistablePointsQuery(query.queryKey),
        },
      }}>
      {children}
    </PersistQueryClientProvider>
  );
}
