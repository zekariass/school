import { defaultShouldDehydrateQuery } from '@tanstack/react-query';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import Constants from 'expo-constants';
import type { ReactNode } from 'react';

import { shouldPersistQuery } from '@/lib/media/url-cache';

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
        // Signed media links are dead after a restart: keep them out of the persisted cache.
        dehydrateOptions: {
          shouldDehydrateQuery: (query) => defaultShouldDehydrateQuery(query) && shouldPersistQuery(query.queryKey),
        },
      }}>
      {children}
    </PersistQueryClientProvider>
  );
}
