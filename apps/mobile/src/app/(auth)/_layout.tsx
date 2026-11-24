import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';

export default function AuthLayout() {
  const t = useTranslations();
  const { colors, text } = useAppTheme();
  return (
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
      <Stack.Screen name="sign-in" />
      <Stack.Screen
        name="student-code"
        options={{
          headerShown: true,
          title: t('mobile.studentCode.title'),
          headerBackButtonDisplayMode: 'minimal',
          headerTintColor: colors.primary,
          headerStyle: { backgroundColor: colors.background },
          headerTitleStyle: { color: colors.foreground, fontFamily: text('heading').fontFamily },
          headerShadowVisible: false,
        }}
      />
    </Stack>
  );
}
