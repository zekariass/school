import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { usePushPermission, usePushPromptDismissed } from '@/lib/notifications/use-push';
import { shouldShowPushCard } from '@/lib/notifications/push-registration';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Card, Text } from './ui';

/**
 * Explains why the app wants to send notifications before the operating system asks. Shown on the
 * home screens to a signed-in user who has not been asked yet; "Not now" hides it for good (it
 * stays reachable in Settings). Nothing here runs at launch.
 */
export function PushPromptCard() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { status, preview } = useSession();
  const { permission, ask } = usePushPermission();
  const { dismissed, dismiss } = usePushPromptDismissed();

  if (!shouldShowPushCard({ signedIn: status === 'signedIn', preview, permission, dismissed })) return null;
  return (
    <Card accessibilityRole="summary" style={{ gap: spacing[3] }}>
      <Text variant="heading">{t('mobile.push.cardTitle')}</Text>
      <Text>{t('mobile.push.cardBody')}</Text>
      <View style={{ gap: spacing[2] }}>
        <Button label={t('mobile.push.cardAllow')} onPress={() => void ask()} />
        <Button variant="ghost" label={t('mobile.push.cardLater')} onPress={dismiss} />
      </View>
    </Card>
  );
}
