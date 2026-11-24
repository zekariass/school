import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { AndroidImportance, IosAuthorizationStatus } from 'expo-notifications';
import { Platform } from 'react-native';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { getCurrentLocale } from '@/lib/i18n/locale';

import { readStoredDevice, storeDevice } from './device';
import { decideRegistration, type PermissionState } from './push-registration';

/** Android channel id; also the plugin's defaultChannel, so pushes without a channel land here. */
export const DEFAULT_CHANNEL_ID = 'default';

/**
 * Foreground pushes show a banner and go to the notification list, silently (no sound, no app
 * icon badge: the in-app bell has the count). Must run before the first notification arrives, so
 * it is set when this module is first imported.
 */
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

export async function ensureAndroidChannel(): Promise<void> {
  if (Platform.OS !== 'android') return;
  await Notifications.setNotificationChannelAsync(DEFAULT_CHANNEL_ID, {
    name: 'General',
    importance: AndroidImportance.DEFAULT,
  });
}

function toState(status: Notifications.NotificationPermissionsStatus): PermissionState {
  if (status.granted) return 'granted';
  // iOS: PROVISIONAL / EPHEMERAL also deliver (quietly)
  const ios = status.ios?.status;
  if (ios === IosAuthorizationStatus.PROVISIONAL || ios === IosAuthorizationStatus.EPHEMERAL) {
    return 'granted';
  }
  return status.canAskAgain === false || status.status === 'denied' ? 'denied' : 'undetermined';
}

export async function getPermissionState(): Promise<PermissionState> {
  try {
    return toState(await Notifications.getPermissionsAsync());
  } catch {
    return 'denied';
  }
}

/** Shows the operating system's prompt (only ever called from a tap on our explanation card). */
export async function requestPermission(): Promise<PermissionState> {
  await ensureAndroidChannel(); // Android 13+ shows no prompt before a channel exists
  try {
    return toState(await Notifications.requestPermissionsAsync());
  } catch {
    return 'denied';
  }
}

function easProjectId(): string | undefined {
  const extra = Constants.expoConfig?.extra as { eas?: { projectId?: string } } | undefined;
  return extra?.eas?.projectId ?? Constants.easConfig?.projectId ?? undefined;
}

export type RegisterOutcome = 'registered' | 'skipped' | 'failed';

/**
 * Registers this device with the API for the current session. Run whenever the user is signed in
 * with permission granted: after sign-in, after the permission is granted, and when the push token
 * changes. The API upserts by token and binds the device to the session that calls it, so calling
 * again after every sign-in is what keeps pushes flowing to the new session.
 *
 * EAS is not linked yet (no project id): then nothing is registered and nothing is shown to the
 * user; development builds log why.
 */
export async function registerForPush(): Promise<RegisterOutcome> {
  const permission = await getPermissionState();
  const projectId = easProjectId();
  const decision = decideRegistration({ permission, projectId, platform: Platform.OS });
  if (decision.action === 'skip') {
    if (__DEV__) console.log(`[push] not registering: ${decision.reason}`);
    return 'skipped';
  }
  try {
    await ensureAndroidChannel();
    const { data: token } = await Notifications.getExpoPushTokenAsync({ projectId });
    const device = await request(async () =>
      unwrap(
        await api.POST('/api/v1/me/push-devices', {
          body: {
            platform: decision.platform,
            provider: 'EXPO',
            token,
            appVersion: Constants.expoConfig?.version,
            deviceModel: Device.modelName ?? undefined,
            locale: getCurrentLocale(),
          },
        }),
      ),
    );
    await storeDevice({ id: device.id, token });
    return 'registered';
  } catch (error) {
    if (__DEV__) console.warn('[push] registration failed', error);
    return 'failed';
  }
}

export { readStoredDevice };
