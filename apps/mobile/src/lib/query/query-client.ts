import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { QueryClient, focusManager, onlineManager } from '@tanstack/react-query';
import * as Network from 'expo-network';
import { AppState } from 'react-native';

import { shouldRetry } from '@/lib/api-error';
import { kv } from '@/lib/storage';

export const QUERY_CACHE_KEY = 'school.queryCache';
/** Cached data older than this is discarded on startup; also the in-memory gcTime. */
export const QUERY_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 60_000,
      // Must be >= the persistence maxAge or entries are dropped before they can be restored.
      gcTime: QUERY_CACHE_MAX_AGE_MS,
      // Show cached data and try the network; don't pause the first fetch while "offline".
      networkMode: 'offlineFirst',
      retry: shouldRetry,
    },
  },
});

export const queryPersister = createAsyncStoragePersister({
  storage: kv,
  key: QUERY_CACHE_KEY,
  throttleTime: 1000,
});

/** Drops cached server data from memory and disk (sign out: it may hold children's data). */
export async function clearQueryCache(): Promise<void> {
  queryClient.clear();
  await queryPersister.removeClient();
}

/**
 * Connects TanStack Query's online and focus state to the device: expo-network
 * connectivity and the app moving to the foreground. Returns a cleanup function.
 */
export function startQueryDeviceListeners(): () => void {
  onlineManager.setEventListener((setOnline) => {
    Network.getNetworkStateAsync()
      .then((state) => setOnline(state.isConnected !== false))
      .catch(() => setOnline(true));
    const subscription = Network.addNetworkStateListener((state) => setOnline(state.isConnected !== false));
    return () => subscription.remove();
  });

  const appState = AppState.addEventListener('change', (status) => {
    focusManager.setFocused(status === 'active');
  });
  return () => appState.remove();
}
