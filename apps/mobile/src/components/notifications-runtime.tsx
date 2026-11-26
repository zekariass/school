import { onlineManager } from '@tanstack/react-query';
import { useEffect } from 'react';
import { AppState } from 'react-native';

import { outbox } from '@/lib/messaging/runtime';
import { useRealtime } from '@/lib/notifications/use-realtime';
import { usePushRuntime } from '@/lib/notifications/use-push';
import { useSession } from '@/lib/session/session-provider';

/**
 * Background plumbing of a signed-in account, no UI: the live WebSocket connection (badge and inbox
 * updates, the messaging doorbell), push device registration and what happens when a notification is tapped, and the memory-only
 * outbox of messages that did not go out (tried again when the phone is online or the app returns to the foreground). Mounted once
 * by the root layout; the developer preview session has no account, so nothing runs for it.
 */
export function NotificationsRuntime() {
  const { status, preview, activeRole } = useSession();
  const active = status === 'signedIn' && !preview;
  useRealtime(active);
  usePushRuntime(active, activeRole);
  useEffect(() => {
    if (!active) return;
    const app = AppState.addEventListener('change', (state) => {
      if (state === 'active') outbox.kick();
    });
    const online = onlineManager.subscribe((isOnline) => {
      if (isOnline) outbox.kick();
    });
    return () => {
      app.remove();
      online();
    };
  }, [active]);
  return null;
}
