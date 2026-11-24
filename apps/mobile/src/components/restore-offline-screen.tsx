import { useState } from 'react';
import { useTranslations } from 'use-intl';

import { useSession } from '@/lib/session/session-provider';

import { Button, EmptyState, Screen } from './ui';

/**
 * Shown instead of the routes when a refresh token is stored but the API cannot be
 * reached at launch. The token is kept: "Try again" restores the session once we are online.
 */
export function RestoreOfflineScreen() {
  const t = useTranslations();
  const { retryRestore, signOut } = useSession();
  const [retrying, setRetrying] = useState(false);

  async function retry() {
    setRetrying(true);
    try {
      await retryRestore();
    } finally {
      setRetrying(false);
    }
  }


  return (
    <Screen edges={['top', 'bottom']}>
      <EmptyState
        icon={{ ios: 'wifi.slash', android: 'wifi_off' }}
        title={t('mobile.offline.title')}
        body={t('mobile.offline.body')}>
        <Button label={t('common.retry')} onPress={retry} loading={retrying} />
        <Button variant="ghost" label={t('auth.signOut')} onPress={() => signOut()} disabled={retrying} />
      </EmptyState>
    </Screen>
  );
}
