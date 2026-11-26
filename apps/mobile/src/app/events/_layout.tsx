import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * The calendar screens above the tabs: the calendar itself (for roles without a calendar tab), one event, and the form that
 * creates or changes one. A notification can open the event first, so each screen has its own back button.
 */
export default function EventsStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '', headerLeft: () => <HeaderBack /> }}>
      <Stack.Screen name="index" options={{ title: t('nav.calendar') }} />
      <Stack.Screen name="[eventId]" options={{ title: t('mobile.calendar.detail.title') }} />
      <Stack.Screen name="compose" options={{ title: t('mobile.calendar.form.titleNew'), presentation: 'modal', headerLeft: undefined }} />
    </Stack>
  );
}
