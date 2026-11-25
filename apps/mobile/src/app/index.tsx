import { Redirect, type Href } from 'expo-router';

import { entryRoute } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { signedOutRoute } from '@/lib/session/sign-in-method';

/**
 * Entry point: signed-out users go to sign-in (students who used a class code come back to the
 * code screen), signed-in users to their active role's home.
 */
export default function Index() {
  const { status, activeRole, profile, signInMethod } = useSession();
  const href = entryRoute(status, activeRole, profile !== null, signedOutRoute(signInMethod));
  return href ? <Redirect href={href as Href} /> : null;
}
