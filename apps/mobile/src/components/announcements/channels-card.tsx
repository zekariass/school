import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Switch, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { channelsKey } from '@/lib/announcements/keys';
import { useChannelPreferences } from '@/lib/announcements/queries';
import { putChannels } from '@/lib/announcements/requests';
import type { CommunicationChannels } from '@/lib/announcements/types';
import { problemOf } from '@/lib/api-error';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Card, Text } from '../ui';

/**
 * Settings > "Communication channels": whether the school may send announcements by email and by text message (both on until
 * switched off). Push and the in-app list are the notification preferences; an urgent announcement never overrides these two.
 */
export function CommunicationChannelsCard() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const prefs = useChannelPreferences();
  const change = useMutation({
    mutationFn: (body: { email?: boolean; sms?: boolean }) => putChannels(body),
    onMutate: (body) => {
      // Shows the choice at once; the answer (or a refetch after an error) settles it.
      client.setQueryData<CommunicationChannels>(channelsKey, (current) => (current ? { ...current, ...body } : current));
    },
    onSuccess: (data) => client.setQueryData(channelsKey, data),
    onError: () => void client.invalidateQueries({ queryKey: channelsKey }),
  });
  const data = prefs.data;

  return (
    <Card>
      <Text variant="heading">{t('mobile.announcements.channels.title')}</Text>
      <Text variant="caption">{t('mobile.announcements.channels.hint')}</Text>
      {prefs.isError ? (
        <View style={{ gap: spacing[2] }}>
          <Text tone="destructive">{problemOf(prefs.error)?.detail ?? t('mobile.announcements.channels.loadError')}</Text>
          <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void prefs.refetch()} />
        </View>
      ) : null}
      {data ? (
        <>
          <Row label={t('mobile.announcements.channels.email')} value={data.email} onChange={(email) => change.mutate({ email })} colors={colors} />
          <Row label={t('mobile.announcements.channels.sms')} value={data.sms && !data.smsBlockedByProvider} disabled={data.smsBlockedByProvider} onChange={(sms) => change.mutate({ sms })} colors={colors} />
          {data.smsBlockedByProvider ? <Text variant="caption">{t('mobile.announcements.channels.smsBlocked')}</Text> : null}
        </>
      ) : null}
      {change.isError ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {problemOf(change.error)?.detail ?? t('mobile.announcements.channels.saveError')}
        </Text>
      ) : null}
    </Card>
  );
}

function Row({ label, value, onChange, disabled = false, colors }: { label: string; value: boolean; onChange: (value: boolean) => void; disabled?: boolean; colors: { primary: string; border: string } }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 44, opacity: disabled ? 0.5 : 1 }}>
      <Text style={{ flex: 1 }}>{label}</Text>
      <Switch accessibilityLabel={label} value={value} disabled={disabled} onValueChange={onChange} trackColor={{ true: colors.primary, false: colors.border }} />
    </View>
  );
}
