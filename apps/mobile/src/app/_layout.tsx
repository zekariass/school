import { DarkTheme, DefaultTheme, Stack, ThemeProvider, router } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState } from 'react';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { NotificationsRuntime } from '@/components/notifications-runtime';
import { RestoreOfflineScreen } from '@/components/restore-offline-screen';
import { SyncRuntime } from '@/components/sync-runtime';
import { bootstrap, type BootstrapResult } from '@/lib/bootstrap';
import { AppIntlProvider } from '@/lib/i18n/intl-provider';
import { PlaceProvider } from '@/lib/country/place';
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
              {/* Zone and calendars of the person or the platform default country (D42); needs the session and the query cache. */}
              <PlaceProvider>
                <RootNavigator />
              </PlaceProvider>
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
  const previousStatus = useRef(status);

  useEffect(() => {
    if (status !== 'loading') SplashScreen.hide();
  }, [status]);

  // Signing in or out (also when the server ends the session) re-enters through the index
  // route, which sends the user to the right place. Launch and the offline screen are
  // handled by the index route itself.
  useEffect(() => {
    const previous = previousStatus.current;
    previousStatus.current = status;
    if ((previous === 'signedIn' && status === 'signedOut') || (previous === 'signedOut' && status === 'signedIn')) {
      router.replace('/');
    }
  }, [status]);

  if (status === 'loading') return null;
  if (status === 'unreachable') return <RestoreOfflineScreen />;

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
      <NotificationsRuntime />
      <SyncRuntime />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}>
        <Stack.Screen name="index" />

        <Stack.Protected guard={!signedIn}>
          <Stack.Screen name="(auth)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === null}>
          <Stack.Screen name="(account)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'teacher'}>
          <Stack.Screen name="(teacher)" />
          {/* A class opened from the Classes tab (roster, students, codes). */}
          <Stack.Screen name="class" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'guardian'}>
          <Stack.Screen name="(guardian)" />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'guardian'}>
          {/* A child's points: school and home, separate. */}
          <Stack.Screen name="child/[studentId]" options={{ ...header, title: t('mobile.points.child.title') }} />
          {/* A child's attendance: absence reports to the school and the history. */}
          <Stack.Screen name="child/[studentId]/attendance" options={{ ...header, title: t('mobile.attendance.guardian.title'), headerLeft: () => <HeaderBack /> }} />
        </Stack.Protected>
        <Stack.Protected guard={signedIn && activeRole === 'student'}>
          <Stack.Screen name="(student)" />
        </Stack.Protected>

        <Stack.Protected guard={signedIn}>
          {/* The feed (M8): class and school feeds, a post, a story, the composer, my posts, approvals. */}
          <Stack.Screen name="feed" />
          {/* The portfolio (M12): a student's own work, one item, the composer, a teacher's quick capture and review queue, a guardian's child page. */}
          <Stack.Screen name="portfolio" />
          {/* The communications centre (M10): the announcements inbox, one announcement, the composer, my announcements, the report. */}
          <Stack.Screen name="announcements" />
          {/* Messaging (M9): one conversation, its people, starting a conversation or a group, scheduled messages, blocked people. */}
          <Stack.Screen name="messages" />
          {/* The calendar (M11): the calendar for roles without a tab, one event (a notification opens it), the create/edit form. */}
          <Stack.Screen name="events" />
          {/* The manager's staff roll (M7d): online only; who sees an entry point comes from /me, the API checks again. */}
          <Stack.Screen name="staff-attendance" options={{ ...header, title: t('mobile.attendance.staff.title'), headerLeft: () => <HeaderBack /> }} />
          <Stack.Screen name="notifications" options={{ ...header, title: t('mobile.notifications.title') }} />
          {/* Guardians decide on consent requests. */}
          <Stack.Screen name="consent" options={{ ...header, title: t('consent.title') }} />
        </Stack.Protected>
        {/* An invitation link (`school://invite/{token}`) and "Have a parent code?": open to anyone, signed in or not. */}
        <Stack.Screen name="invite/[token]" options={{ ...header, title: t('invite.pageTitle') }} />
        <Stack.Screen name="parent-code" options={{ ...header, title: t('family.connect.title') }} />
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
