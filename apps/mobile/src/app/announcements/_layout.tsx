import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * The announcements: the inbox, one announcement, "my announcements", the composer and the report. A stack above the tabs of
 * every role (a notification can open any of them first, so each screen has its own back button).
 */
export default function AnnouncementsStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '', headerLeft: () => <HeaderBack /> }}>
      <Stack.Screen name="index" options={{ title: t('mobile.announcements.title') }} />
      <Stack.Screen name="[announcementId]" options={{ title: t('mobile.announcements.detail.title') }} />
      <Stack.Screen name="mine" options={{ title: t('mobile.announcements.mine.title') }} />
      <Stack.Screen name="compose" options={{ title: t('mobile.announcements.compose.title'), presentation: 'modal', headerLeft: undefined }} />
      <Stack.Screen name="report" options={{ title: t('mobile.announcements.report.title') }} />
    </Stack>
  );
}
