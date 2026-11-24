import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

export default function AuthLayout() {
  const t = useTranslations();
  const { colors, text } = useAppTheme();
  const header = {
    headerShown: true,
    headerBackButtonDisplayMode: 'minimal',
    headerTintColor: colors.primary,
    headerStyle: { backgroundColor: colors.background },
    headerTitleStyle: { color: colors.foreground, fontFamily: text('heading').fontFamily },
    headerShadowVisible: false,
  } as const;
  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
      <Stack.Screen name="sign-in" />
      <Stack.Screen name="verify" options={{ ...header, title: t('mobile.verify.title') }} />
      <Stack.Screen name="student-code" options={{ ...header, title: t('mobile.studentCode.title') }} />
    </Stack>
  );
}
