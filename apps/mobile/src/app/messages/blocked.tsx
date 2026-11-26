import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ActivityIndicator, Alert, FlatList, RefreshControl, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { useParticipantText } from '@/components/messaging/use-participant-text';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { problemOf } from '@/lib/api-error';
import { INBOX_PREFIX, blocksKey } from '@/lib/messaging/keys';
import { useBlocks } from '@/lib/messaging/queries';
import { unblock } from '@/lib/messaging/requests';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The people the person blocked, in the words they were shown in the conversation (a name or a role label). A block is by
 * conversation member, never by user id; the other person is not told. Removing a block makes the conversation usable again.
 */
export default function BlockedScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const client = useQueryClient();
  const personText = useParticipantText();
  const blocks = useBlocks();
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function remove(id: string) {
    if (busy) return;
    setBusy(id);
    setProblem(null);
    try {
      await unblock(id);
      await client.invalidateQueries({ queryKey: blocksKey });
      void client.invalidateQueries({ queryKey: INBOX_PREFIX });
    } catch (error) {
      setProblem(problemOf(error)?.detail ?? t('mobile.messaging.errors.generic'));
    } finally {
      setBusy(null);
    }
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={blocks.data ?? []}
        keyExtractor={(item) => item.id}
        contentContainerStyle={{ flexGrow: 1, padding: spacing[5], gap: spacing[3] }}
        refreshControl={<RefreshControl refreshing={blocks.isRefetching} onRefresh={() => void blocks.refetch()} tintColor={colors.primary} />}
        ListHeaderComponent={
          <View style={{ gap: spacing[2], paddingBottom: spacing[2] }}>
            <Text tone="muted">{t('mobile.messaging.blocked.intro')}</Text>
            {problem ? <Text tone="destructive" accessibilityLiveRegion="polite">{problem}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          blocks.isPending ? (
            <ActivityIndicator color={colors.primary} />
          ) : blocks.isError ? (
            <ProblemBanner error={blocks.error} fallbackTitle={t('mobile.messaging.blocked.loadFailedTitle')} fallbackBody={t('mobile.messaging.blocked.loadFailedBody')} onRetry={() => void blocks.refetch()} />
          ) : (
            <EmptyState icon={{ ios: 'hand.raised', android: 'block' }} title={t('mobile.messaging.blocked.emptyTitle')} body={t('mobile.messaging.blocked.emptyBody')} />
          )
        }
        renderItem={({ item }) => (
          <Card>
            <Text variant="label">{item.person ? personText({ ...item.person, self: false }, undefined) : t('mobile.messaging.label.member')}</Text>
            <Button
              fullWidth={false}
              variant="ghost"
              label={t('mobile.messaging.blocked.remove')}
              loading={busy === item.id}
              onPress={() =>
                Alert.alert(t('mobile.messaging.blocked.removeTitle'), t('mobile.messaging.blocked.removeBody'), [
                  { text: t('common.cancel'), style: 'cancel' },
                  { text: t('mobile.messaging.blocked.remove'), onPress: () => void remove(item.id) },
                ])
              }
            />
          </Card>
        )}
      />
    </SafeAreaView>
  );
}
