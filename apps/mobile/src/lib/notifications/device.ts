import { api } from '@/lib/api';
import { assertOk, request } from '@/lib/api-error';
import { kv } from '@/lib/storage';

/**
 * The push device this install registered: its API id (needed to delete it on sign-out) and the
 * Expo push token (to detect a changed token). Neither is a credential, so the plain kv store is
 * fine; auth tokens never go here.
 */
export const PUSH_DEVICE_KEY = 'school.push.device';
export const PUSH_PROMPT_DISMISSED_KEY = 'school.push.promptDismissed';

export interface StoredDevice {
  id: string;
  token: string;
}

export async function readStoredDevice(): Promise<StoredDevice | null> {
  try {
    const raw = await kv.getItem(PUSH_DEVICE_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<StoredDevice>;
    return typeof value.id === 'string' && typeof value.token === 'string' ? { id: value.id, token: value.token } : null;
  } catch {
    return null;
  }
}

export async function storeDevice(device: StoredDevice): Promise<void> {
  await kv.setItem(PUSH_DEVICE_KEY, JSON.stringify(device));
}

const UNREGISTER_TIMEOUT_MS = 3000;

/**
 * Sign-out: stops pushes to this device while the access token still works. Best effort and
 * bounded in time; the server also revokes the device when the session ends.
 */
export async function unregisterStoredPushDevice(): Promise<void> {
  const device = await readStoredDevice();
  if (!device) return;
  try {
    await Promise.race([
      request(async () =>
        assertOk(await api.DELETE('/api/v1/me/push-devices/{id}', { params: { path: { id: device.id } } })),
      ),
      new Promise<void>((resolve) => setTimeout(resolve, UNREGISTER_TIMEOUT_MS)),
    ]);
  } catch (error) {
    if (__DEV__) console.warn('[push] could not remove the device on sign-out', error);
  }
  await kv.removeItem(PUSH_DEVICE_KEY).catch(() => undefined);
}
