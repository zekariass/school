import { Redirect, router } from 'expo-router';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Card, Screen, Text } from '@/components/ui';
import { ROLE_LABEL_KEY, ROLES, homeRouteFor, type Role } from '@/lib/session/roles';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * DEVELOPMENT ONLY role previewer: starts an in-memory session (no tokens) so the
 * role shells can be seen before real sign-in exists (M1). Unreachable in release
 * builds: the route is not registered, the entry link is hidden, and this screen
 * redirects away as a last line of defence.
 */
export default function DevRolePreviewScreen() {
  const t = useTranslations();
  const { startPreview } = useSession();
  const { spacing } = useAppTheme();

  if (!__DEV__) return <Redirect href="/" />;

  function preview(role: Role) {
    startPreview(role);
    router.replace(homeRouteFor(role));
  }

  return (
    <Screen scroll edges={['bottom']}>
      <Card>
        <Text variant="heading">{t('mobile.dev.warningTitle')}</Text>
        <Text tone="muted">{t('mobile.dev.warningBody')}</Text>
      </Card>
      <View style={{ gap: spacing[3] }}>
        {ROLES.map((role) => (
          <Button key={role} label={t(ROLE_LABEL_KEY[role])} onPress={() => preview(role)} />
        ))}
      </View>
    </Screen>
  );
}
