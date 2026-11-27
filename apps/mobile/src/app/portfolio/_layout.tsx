import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * The portfolio: the student's own items, one item, the composer, a teacher's quick capture and review queue, and a guardian's
 * page for one child. A stack above the tabs of every role (a notification can open an item first, so every screen has its own
 * back button).
 */
export default function PortfolioStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '', headerLeft: () => <HeaderBack /> }}>
      <Stack.Screen name="mine" options={{ title: t('mobile.portfolio.mine.title') }} />
      <Stack.Screen name="item/[itemId]" options={{ title: t('mobile.portfolio.item.title') }} />
      <Stack.Screen name="compose" options={{ title: t('mobile.portfolio.compose.title'), presentation: 'modal', headerLeft: undefined }} />
      <Stack.Screen name="capture" options={{ title: t('mobile.portfolio.capture.title') }} />
      <Stack.Screen name="queue/[classId]" options={{ title: t('mobile.portfolio.queue.title') }} />
      <Stack.Screen name="child/[studentId]" options={{ title: t('mobile.portfolio.child.title') }} />
    </Stack>
  );
}
