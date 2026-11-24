import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { RefreshControl, View } from 'react-native';
import { useLocale, useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, Screen, Text } from '@/components/ui';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { metaQuery } from '@/lib/meta';
import { useAppTheme } from '@/lib/theme/theme-provider';

export default function StatusScreen() {
  const t = useTranslations();
  const locale = useLocale();
  const formatInstant = useFormatInstant();
  const { colors, spacing } = useAppTheme();
  const query = useQuery(metaQuery);
  const [pulling, setPulling] = useState(false);

  const seconds = (ms: number) =>
    new Intl.NumberFormat(locale, { signDisplay: 'always', maximumFractionDigits: 2 }).format(ms / 1000);

  async function refresh() {
    setPulling(true);
    try {
      await query.refetch();
    } finally {
      setPulling(false);
    }
  }

  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={pulling} onRefresh={refresh} tintColor={colors.primary} />}>
      <Text tone="muted">{t('mobile.status.description')}</Text>

      {query.isPending ? (
        <Text accessibilityLiveRegion="polite">{t('common.loading')}</Text>
      ) : query.isError ? (
        <ProblemBanner
          error={query.error}
          fallbackTitle={t('status.errorTitle')}
          fallbackBody={t('status.errorBody')}
          onRetry={() => query.refetch()}
        />
      ) : (
        <View style={{ gap: spacing[3] }} accessibilityLiveRegion="polite">
          <Detail label={t('status.service')} value={query.data.meta.service} />
          <Detail label={t('status.apiVersion')} value={query.data.meta.apiVersion} />
          <Detail label={t('status.buildVersion')} value={query.data.meta.buildVersion} />
          <Detail label={t('status.serverTime')} value={formatInstant(query.data.meta.serverTime)} />
          <Detail
            label={t('status.clockSkew')}
            value={t('status.clockSkewValue', { seconds: seconds(query.data.skewMs) })}
          />
        </View>
      )}

      <Button
        variant="ghost"
        label={t('common.retry')}
        loading={query.isFetching && !pulling}
        onPress={() => query.refetch()}
      />
    </Screen>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <Card style={{ gap: 4, padding: 16, borderRadius: 20 }} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text variant="caption">{label}</Text>
      <Text variant="heading" selectable>
        {value}
      </Text>
    </Card>
  );
}
