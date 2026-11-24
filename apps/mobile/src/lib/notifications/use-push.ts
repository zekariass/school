import * as Notifications from 'expo-notifications';
import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { kv } from '@/lib/storage';
import { homeRouteFor, type Role } from '@/lib/session/roles';

import { PUSH_PROMPT_DISMISSED_KEY } from './device';
import { notificationIdFromData, pathFromNotificationData, routeForPath } from './routes';
import { getPermissionState, registerForPush, requestPermission } from './push';
import type { PermissionState } from './push-registration';
import { useInboxActions } from './queries';

/** The OS permission state; re-read when the app returns to the foreground (the user may have used Settings). */
export function usePushPermission() {
  const [permission, setPermission] = useState<PermissionState | null>(null);

  const refresh = useCallback(() => getPermissionState().then(setPermission), []);

  useEffect(() => {
    let current = true;
    void getPermissionState().then((state) => current && setPermission(state));
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refresh();
    });
    return () => {
      current = false;
      subscription.remove();
    };
  }, [refresh]);

  /** Shows the OS prompt (call from a tap), then registers the device if it was allowed. */
  const ask = useCallback(async () => {
    const result = await requestPermission();
    setPermission(result);
    if (result === 'granted') void registerForPush();
    return result;
  }, []);

  return { permission, refresh, ask };
}

/** Whether the person dismissed the explanation card (a preference, not a credential). */
export function usePushPromptDismissed() {
  const [dismissed, setDismissed] = useState(true); // hidden until we know
  useEffect(() => {
    kv.getItem(PUSH_PROMPT_DISMISSED_KEY)
      .then((value) => setDismissed(value === '1'))
      .catch(() => setDismissed(false));
  }, []);
  const dismiss = useCallback(() => {
    setDismissed(true);
    kv.setItem(PUSH_PROMPT_DISMISSED_KEY, '1').catch(() => undefined);
  }, []);
  return { dismissed, dismiss };
}

/**
 * Push plumbing for a signed-in session: registers the device (when allowed), registers again when
 * the push token changes, and opens the right screen when a notification is tapped, including the
 * one that launched the app.
 */
export function usePushRuntime(enabled: boolean, activeRole: Role | null): void {
  const { markRead } = useInboxActions();
  const handled = useRef<string | null>(null);

  useEffect(() => {
    if (!enabled) return;
    void registerForPush();
    const subscription = Notifications.addPushTokenListener(() => void registerForPush());
    return () => subscription.remove();
  }, [enabled]);

  const response = Notifications.useLastNotificationResponse();
  useEffect(() => {
    if (!enabled || !response) return;
    if (response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return;
    const request = response.notification.request;
    const key = `${request.identifier}:${response.notification.date}`;
    if (handled.current === key) return;
    handled.current = key;

    const data = request.content.data;
    const id = notificationIdFromData(data);
    if (id) markRead.mutate(id);
    const target = routeForPath(pathFromNotificationData(data), activeRole ? homeRouteFor(activeRole) : null);
    if (target) {
      const go = () => router.navigate(target as Parameters<typeof router.navigate>[0]);
      try {
        go();
      } catch {
        // cold start from a tap: the navigator may not be mounted yet
        setTimeout(go, 500);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- handle each response once
  }, [enabled, response, activeRole]);
}
