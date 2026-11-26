import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { Linking, Switch, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { api } from '@/lib/api';
import { problemOf, request, unwrap } from '@/lib/api-error';
import { preferencesKey, settingsKey } from '@/lib/notifications/keys';
import { quietHoursPayload } from '@/lib/notifications/quiet-hours';
import { preferencesQuery, settingsQuery } from '@/lib/notifications/queries';
import { usePushPermission } from '@/lib/notifications/use-push';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Card, Text, TextField } from './ui';

/** Settings > Notifications: phone permission, which kinds reach the phone, quiet hours. */
export function NotificationSettings() {
  return (
    <>
      <PermissionCard />
      <PreferencesCard />
      <QuietHoursCard />
    </>
  );
}

function PermissionCard() {
  const t = useTranslations();
  const { permission, ask } = usePushPermission();
  return (
    <Card>
      <Text variant="heading">{t('mobile.notifications.settings.title')}</Text>
      {permission === 'granted' ? <Text>{t('mobile.notifications.settings.pushOn')}</Text> : null}
      {permission === 'undetermined' ? (
        <>
          <Text>{t('mobile.notifications.settings.pushUndetermined')}</Text>
          <Button label={t('mobile.notifications.settings.allow')} onPress={() => void ask()} />
        </>
      ) : null}
      {permission === 'denied' ? (
        <>
          <Text>{t('mobile.notifications.settings.pushDenied')}</Text>
          <Button variant="secondary" label={t('mobile.notifications.settings.openSettings')} onPress={() => void Linking.openSettings()} />
        </>
      ) : null}
    </Card>
  );
}

function PreferencesCard() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const prefs = useQuery(preferencesQuery);
  const change = useMutation({
    mutationFn: async (c: { typeCode: string; enabled: boolean }) =>
      request(async () =>
        unwrap(
          await api.PUT('/api/v1/me/notification-preferences', {
            body: { changes: [{ typeCode: c.typeCode, channel: 'PUSH', enabled: c.enabled }] },
          }),
        ),
      ),
    onSuccess: (data) => client.setQueryData(preferencesKey, data),
    onError: () => client.invalidateQueries({ queryKey: preferencesKey }),
  });

  return (
    <Card>
      <Text variant="heading">{t('mobile.notifications.settings.preferencesTitle')}</Text>
      <Text variant="caption">{t('mobile.notifications.settings.preferencesHint')}</Text>
      {prefs.isError ? <Text tone="destructive">{t('mobile.notifications.settings.loadError')}</Text> : null}
      {prefs.data?.categories.map((category) => (
        <View key={category.code} style={{ gap: spacing[2] }}>
          <Text variant="label" tone="primary">
            {category.label}
          </Text>
          {category.types.map((type) => {
            const push = type.channels.find((c) => c.channel === 'PUSH');
            if (!push) return null;
            return (
              <View key={type.code} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], minHeight: 44 }}>
                <View style={{ flex: 1 }}>
                  <Text>{type.label}</Text>
                  {push.locked ? <Text variant="caption">{t('mobile.notifications.settings.locked')}</Text> : null}
                  {type.urgentOverridesPreferences ? (
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 2 }}>
                      <SymbolView name={{ ios: 'lock.fill', android: 'lock', web: 'lock' }} size={14} tintColor={colors.mutedForeground} />
                      <Text variant="caption" style={{ flex: 1 }}>
                        {t('mobile.announcements.preferences.urgentLock')}
                      </Text>
                    </View>
                  ) : null}
                </View>
                <Switch
                  accessibilityLabel={t('mobile.notifications.settings.pushToggle', { name: type.label })}
                  value={push.enabled}
                  disabled={push.locked}
                  trackColor={{ true: colors.primary }}
                  onValueChange={(enabled) => change.mutate({ typeCode: type.code, enabled })}
                />
              </View>
            );
          })}
        </View>
      ))}
    </Card>
  );
}

function QuietHoursCard() {
  const settings = useQuery(settingsQuery);
  // The form starts from what the server has (remounted once, when it arrives).
  return (
    <QuietHoursForm
      key={settings.isSuccess ? 'loaded' : 'loading'}
      initialStart={settings.data?.quietHoursStart ?? ''}
      initialEnd={settings.data?.quietHoursEnd ?? ''}
      timezone={settings.data?.timezone ?? ''}
    />
  );
}

function QuietHoursForm({ initialStart, initialEnd, timezone }: { initialStart: string; initialEnd: string; timezone: string }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const client = useQueryClient();
  const [start, setStart] = useState(initialStart);
  const [end, setEnd] = useState(initialEnd);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  const save = useMutation({
    mutationFn: async (body: { quietHoursStart?: string; quietHoursEnd?: string }) =>
      request(async () => unwrap(await api.PUT('/api/v1/me/notification-settings', { body: { ...body, digestMode: 'OFF' } }))),
    onSuccess: (data) => {
      client.setQueryData(settingsKey, data);
      setMessage({ text: t('mobile.notifications.settings.saved'), error: false });
    },
    onError: (error) =>
      setMessage({ text: problemOf(error)?.detail ?? t('mobile.notifications.settings.saveError'), error: true }),
  });

  function submit(clear: boolean) {
    if (clear) {
      setStart('');
      setEnd('');
      setMessage(null);
      save.mutate({});
      return;
    }
    const payload = quietHoursPayload({ start, end });
    if (!payload.ok) {
      setMessage({
        text: t(payload.reason === 'incomplete' ? 'mobile.notifications.settings.errorIncomplete' : 'mobile.notifications.settings.errorInvalid'),
        error: true,
      });
      return;
    }
    setMessage(null);
    save.mutate({ quietHoursStart: payload.quietHoursStart, quietHoursEnd: payload.quietHoursEnd });
  }
  return (
    <Card>
      <Text variant="heading">{t('mobile.notifications.settings.quietTitle')}</Text>
      <Text variant="caption">{t('mobile.notifications.settings.quietHint', { timezone })}</Text>
      <View style={{ flexDirection: 'row', gap: spacing[3] }}>
        <View style={{ flex: 1 }}>
          <TextField
            label={t('mobile.notifications.settings.startLabel')}
            value={start}
            onChangeText={setStart}
            placeholder="22:00"
            keyboardType="numbers-and-punctuation"
            maxLength={5}
          />
        </View>
        <View style={{ flex: 1 }}>
          <TextField
            label={t('mobile.notifications.settings.endLabel')}
            value={end}
            onChangeText={setEnd}
            placeholder="06:00"
            keyboardType="numbers-and-punctuation"
            maxLength={5}
          />
        </View>
      </View>
      {message ? (
        <Text variant="caption" tone={message.error ? 'destructive' : 'muted'} accessibilityLiveRegion="polite">
          {message.text}
        </Text>
      ) : null}
      <Button label={t('mobile.notifications.settings.save')} loading={save.isPending} onPress={() => submit(false)} />
      <Button variant="ghost" label={t('mobile.notifications.settings.clear')} disabled={save.isPending} onPress={() => submit(true)} />
    </Card>
  );
}
