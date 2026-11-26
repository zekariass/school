import { defaultShouldDehydrateQuery } from '@tanstack/react-query';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import Constants from 'expo-constants';
import type { ReactNode } from 'react';

import { QUERY_CACHE_MAX_AGE_MS, queryClient, queryPersister } from './query-client';
import { isPersistableQuery } from './persistence';

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
        // What stays out of the persisted cache (media links, notifications, student records, the guardian screens, points,
        // attendance, the feed, the announcements...) is decided by `isPersistableQuery` (tested). What a teacher needs offline
        // (roster, roll, skills, totals, templates) is stored by `lib/offline` in its own owner-scoped table; login codes and
        // parent codes never enter a query at all.
        dehydrateOptions: {
          shouldDehydrateQuery: (query) => defaultShouldDehydrateQuery(query) && isPersistableQuery(query.queryKey),
        },
      }}>
      {children}
    </PersistQueryClientProvider>
  );
}
