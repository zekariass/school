import { useRealtime } from '@/lib/notifications/use-realtime';
import { usePushRuntime } from '@/lib/notifications/use-push';
import { useSession } from '@/lib/session/session-provider';

/**
 * Background plumbing of a signed-in account, no UI: the live WebSocket connection (badge and inbox
 * updates), push device registration and what happens when a notification is tapped. Mounted once
 * by the root layout; the developer preview session has no account, so nothing runs for it.
 */
export function NotificationsRuntime() {
  const { status, preview, activeRole } = useSession();
  const active = status === 'signedIn' && !preview;
  useRealtime(active);
  usePushRuntime(active, activeRole);
  return null;
}
