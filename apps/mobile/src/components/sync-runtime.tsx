import { useEffect } from 'react';

import { useMe } from '@/lib/auth/use-me';
import { useSession } from '@/lib/session/session-provider';
import { startSyncRuntime } from '@/lib/sync/runtime';

/**
 * Background plumbing for teachers, no UI: opens the offline cache for the signed-in teacher and sends what waits
 * in the outbox (attendance marks, point awards: now, on reconnect, when the app returns to the foreground, and on
 * a timer) for as long as a teacher account is signed in, whichever role tab is open. The developer preview has no
 * account behind it, so nothing runs for it.
 */
export function SyncRuntime() {
  const { status, preview, roles } = useSession();
  const isTeacher = roles.includes('teacher');
  const active = status === 'signedIn' && !preview && isTeacher;
  const me = useMe();
  const userId = active ? me.data?.id : undefined;

  useEffect(() => {
    if (!userId) return;
    return startSyncRuntime(userId);
  }, [userId]);

  return null;
}
