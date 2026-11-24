import { getDeviceId } from '@/lib/db/device-id';
import { getDatabase } from '@/lib/db/database';
import { loadPreferences, type StoredPreferences } from '@/lib/preferences/preferences-provider';

export interface BootstrapResult {
  preferences: StoredPreferences;
}

/**
 * Everything that must be ready before the first screen (the splash stays up until
 * then): persisted preferences, the migrated local database and the device id.
 * Session restore runs in the SessionProvider and also holds the splash.
 * Fonts are embedded natively (expo-font config plugin), so there is nothing to await.
 */
export async function bootstrap(): Promise<BootstrapResult> {
  const [preferences] = await Promise.all([
    loadPreferences(),
    getDatabase().catch((error: unknown) => {
      // The outbox isn't used yet; don't brick the app over it, but make it loud in dev.
      console.error('[db] could not open or migrate school.db', error);
    }),
    getDeviceId().catch((error: unknown) => {
      console.error('[device] could not read or create the device id', error);
    }),
  ]);
  return { preferences };
}
