import { Stack } from 'expo-router';

import { useAppTheme } from '@/lib/theme/theme-provider';

/** Signed-in screens for accounts without a role shell (see `lib/session/me-roles.ts`). */
export default function AccountLayout() {
  const { colors } = useAppTheme();
  return <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }} />;
}
