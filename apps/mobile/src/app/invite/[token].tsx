import { useLocalSearchParams } from 'expo-router';

import { Dead, InviteFlow } from '@/components/family/invite-flow';
import { looksLikeInviteToken } from '@school/validation/guardians';

/**
 * The screen an invitation link opens (`school://invite/{token}`, or the https link when the app is
 * installed; see `+native-intent.ts`). A token that cannot be one is as dead as an unknown one.
 */
export default function InviteScreen() {
  const params = useLocalSearchParams<{ token?: string | string[] }>();
  const raw = Array.isArray(params.token) ? params.token[0] : params.token;
  return looksLikeInviteToken(raw) ? <InviteFlow keyOf={{ token: raw }} /> : <Dead />;
}
