import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ActivityIndicator, Alert, FlatList, RefreshControl, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { goToConversation } from '@/components/messaging/conversation-row';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { formatInstant } from '@/lib/calendar/format-instant';
import { problemOf } from '@/lib/api-error';
import { scheduledKey } from '@/lib/messaging/keys';
import { useConversation, useMessagingCapabilities, useScheduled } from '@/lib/messaging/queries';
import { deleteMessage } from '@/lib/messaging/requests';
import type { MessageItem } from '@/lib/messaging/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The messages the person scheduled and that are not sent yet. The time is shown in the school's time zone (the capabilities of the
 * conversation's school), never the phone's. A scheduled message can be withdrawn; at send time the API checks again that the
 * sender may still write, and tells them if not.
 */
export default function ScheduledScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const scheduled = useScheduled();
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  async function cancel(message: MessageItem) {
    if (busy) return;
    setBusy(message.id);
    setProblem(null);
    try {
      await deleteMessage(message.conversationId, message.id);
      await client.invalidateQueries({ queryKey: scheduledKey });
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={scheduled.data ?? []}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ flexGrow: 1, padding: spacing[5], gap: spacing[3] }}
        refreshControl={<RefreshControl refreshing={scheduled.isRefetching} onRefresh={() => void scheduled.refetch()} tintColor={colors.primary} />}
        ListHeaderComponent={
          <View style={{ gap: spacing[2], paddingBottom: spacing[2] }}>
            <Text tone="muted">{t('mobile.messaging.scheduled.intro')}</Text>
            {problem ? <Text tone="destructive" accessibilityLiveRegion="polite">{problem}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          scheduled.isPending ? (
            <ActivityIndicator color={colors.primary} />
          ) : scheduled.isError ? (
            <ProblemBanner error={scheduled.error} fallbackTitle={t('mobile.messaging.scheduled.loadFailedTitle')} fallbackBody={t('mobile.messaging.scheduled.loadFailedBody')} onRetry={() => void scheduled.refetch()} />
          ) : (
            <EmptyState icon={{ ios: 'clock', android: 'schedule' }} title={t('mobile.messaging.scheduled.emptyTitle')} body={t('mobile.messaging.scheduled.emptyBody')} />
          )
        }
        renderItem={({ item }) => (
          <ScheduledRow
            message={item}
            busy={busy === item.id}
            onCancel={() =>
              Alert.alert(t('mobile.messaging.scheduled.cancelTitle'), t('mobile.messaging.scheduled.cancelBody'), [
                { text: t('common.cancel'), style: 'cancel' },
                { text: t('mobile.messaging.scheduled.cancelConfirm'), style: 'destructive', onPress: () => void cancel(item) },
              ])
            }
          />
        )}
      />
    </SafeAreaView>
  );
}

function ScheduledRow({ message, busy, onCancel }: { message: MessageItem; busy: boolean; onCancel: () => void }) {
  const t = useTranslations();
  const { locale, calendar } = usePreferences();
  const conversation = useConversation(message.conversationId);
  const caps = useMessagingCapabilities(conversation.data?.summary.schoolId);
  const zone = caps.data?.timeZone;
  const when = message.scheduledFor ? formatInstant(message.scheduledFor, { locale, calendar, ...(zone ? { timeZone: zone } : {}) }) : '';
  return (
    <Card>
      <Text variant="caption">{t('mobile.messaging.scheduled.willSend', { when })}</Text>
      {zone ? <Text variant="caption">{zone.replace(/_/g, ' ')}</Text> : null}
      {message.body ? <Text numberOfLines={4}>{message.body}</Text> : <Text tone="muted">{t('mobile.messaging.message.photos', { count: message.attachments.length })}</Text>}
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Button fullWidth={false} variant="ghost" label={t('mobile.messaging.scheduled.open')} onPress={() => goToConversation(message.conversationId)} />
        <Button fullWidth={false} variant="destructive" label={t('mobile.messaging.scheduled.cancelAction')} loading={busy} onPress={onCancel} />
      </View>
    </Card>
  );
}

