import { useEffect } from 'react';

import { useMe } from '@/lib/auth/use-me';
import { startAwardRuntime } from '@/lib/points/award-runtime';
import { useSession } from '@/lib/session/session-provider';

/**
 * Background plumbing for teachers, no UI: sends the queued awards (now, on reconnect, when the app returns to
 * the foreground, and on a timer) for as long as a teacher account is signed in, whichever role tab is open.
 * The developer preview has no account behind it, so nothing runs for it.
 */
export function PointsRuntime() {
  const { status, preview, roles } = useSession();
  const isTeacher = roles.includes('teacher');
  const active = status === 'signedIn' && !preview && isTeacher;
  const me = useMe();
  const userId = active ? me.data?.id : undefined;

  useEffect(() => {
    if (!userId) return;
    return startAwardRuntime(userId);
  }, [userId]);

  return null;
}
