import { useTranslations } from 'use-intl';

import { Button, EmptyState } from '../ui';

/**
 * What an offline phone shows instead of any portfolio list or item: the portfolio is children's own work and is never kept on
 * the phone, so without a connection there is simply nothing to show (and nothing old is shown in its place).
 */
export function NeedsConnection({ onRetry }: { onRetry?: () => void }) {
  const t = useTranslations();
  return (
    <EmptyState icon={{ ios: 'wifi.slash', android: 'wifi_off' }} title={t('mobile.portfolio.offline.title')} body={t('mobile.portfolio.offline.body')}>
      {onRetry ? <Button variant="ghost" label={t('common.retry')} onPress={onRetry} /> : null}
    </EmptyState>
  );
}
