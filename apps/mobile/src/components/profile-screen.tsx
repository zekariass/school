import { router } from 'expo-router';
import { useTranslations } from 'use-intl';

import { ROLE_LABEL_KEY, type Role } from '@/lib/session/roles';

import { Button, EmptyState, Screen } from './ui';

/** Profile tab shared by every role: placeholder content plus the way into Settings. */
export function ProfileScreen({ role }: { role: Role }) {
  const t = useTranslations();
  return (
    <Screen>
      <EmptyState
        icon={{ ios: 'person.crop.circle', android: 'account_circle' }}
        title={t(ROLE_LABEL_KEY[role])}
        body={t('mobile.empty.profile.body')}>
        <Button label={t('nav.settings')} onPress={() => router.push('/settings')} />
      </EmptyState>
    </Screen>
  );
}
