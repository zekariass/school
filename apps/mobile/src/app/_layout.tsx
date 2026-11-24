import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { useTranslations } from 'use-intl';

import { bootstrap, type BootstrapResult } from '@/lib/bootstrap';
import { AppIntlProvider } from '@/lib/i18n/intl-provider';
import { PreferencesProvider } from '@/lib/preferences/preferences-provider';
import { AppQueryProvider } from '@/lib/query/query-provider';
import { startQueryDeviceListeners } from '@/lib/query/query-client';
import { SessionProvider, useSession } from '@/lib/session/session-provider';
import { AppThemeProvider, useAppTheme } from '@/lib/theme/theme-provider';

// Keep the native splash up until preferences, the database and the session are ready.
SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const [booted, setBooted] = useState<BootstrapResult | null>(null);

  useEffect(() => {
    bootstrap().then(setBooted);
  }, []);
  useEffect(() => startQueryDeviceListeners(), []);

  if (!booted) return null;

  return (
    <PreferencesProvider initial={booted.preferences}>
      <AppThemeProvider>
        <AppIntlProvider>
          <AppQueryProvider>
            <SessionProvider>
              <RootNavigator />
            </SessionProvider>
          </AppQueryProvider>
        </AppIntlProvider>
      </AppThemeProvider>
    </PreferencesProvider>
  );
}

function RootNavigator() {
  const t = useTranslations();
  const { colors, scheme, text } = useAppTheme();
  const { status, activeRole } = useSession();

  useEffect(() => {
    if (status !== 'loading') SplashScreen.hide();
  }, [status]);

  if (status === 'loading') return null;

  const signedIn = status === 'signedIn';
  const base = scheme === 'dark' ? DarkTheme : DefaultTheme;
  const navigationTheme = {
    ...base,
    colors: {
      ...base.colors,
      primary: colors.primary,
      background: colors.background,
      card: colors.background,
      text: colors.foreground,
      border: colors.border,
    },
  };
  const header = {
    headerShown: true,
    headerTintColor: colors.primary,
    headerStyle: { backgroundColor: colors.background },
    headerTitleStyle: { color: colors.foreground, fontFamily: text('heading').fontFamily },
    headerBackButtonDisplayMode: 'minimal',
    headerShadowVisible: false,
  } as const;

  return (
    <ThemeProvider value={navigationTheme}>
      <StatusBar style="auto" />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
        <Stack.Screen name="index" />

        <Stack.Protected guard={!signedIn}>
          <Stack.Screen name="(auth)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'teacher'}>
          <Stack.Screen name="(teacher)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'guardian'}>
          <Stack.Screen name="(guardian)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'student'}>
          <Stack.Screen name="(student)" />
        </Stack.Protected>

        <Stack.Screen name="settings" options={{ ...header, title: t('nav.settings') }} />
        <Stack.Screen name="status" options={{ ...header, title: t('status.title') }} />
        {/* DEVELOPMENT ONLY: not part of the route table in release builds. */}
        <Stack.Protected guard={__DEV__}>
          <Stack.Screen name="dev" options={{ ...header, title: t('mobile.dev.title') }} />
        </Stack.Protected>
      </Stack>
    </ThemeProvider>
  );
}
