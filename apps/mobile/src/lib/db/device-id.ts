import { randomUUID } from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

export const DEVICE_ID_KEY = 'school.deviceId';

let cached: string | undefined;

/**
 * Stable per-install device id (UUID). The sync API de-duplicates operations by
 * (device id, client_operation_id), so the id must survive app restarts.
 */
export async function getDeviceId(): Promise<string> {
  if (cached) return cached;
  let id = await SecureStore.getItemAsync(DEVICE_ID_KEY);
  if (!id) {
    id = randomUUID();
    await SecureStore.setItemAsync(DEVICE_ID_KEY, id);
  }
  cached = id;
  return id;
}
