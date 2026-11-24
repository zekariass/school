import { Redirect, type Href } from 'expo-router';

import { entryRoute } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';

/** Entry point: signed-out users go to sign-in, signed-in users to their active role's home. */
export default function Index() {
  const { status, activeRole } = useSession();
  const href = entryRoute(status, activeRole);
  return href ? <Redirect href={href as Href} /> : null;
}
