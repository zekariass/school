import { useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { ActivityIndicator, Alert, FlatList, RefreshControl, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { reportKey } from '@/lib/announcements/keys';
import { remindProblemKind, retryAfterHours } from '@/lib/announcements/problems';
import { useAnnouncementQuery, useRecipients, useReport } from '@/lib/announcements/queries';
import { remind } from '@/lib/announcements/requests';
import { REPORT_FILTER, REPORT_NONE, ROLE_LABEL } from '@/lib/announcements/labels';
import { percent, recipientFilters } from '@/lib/announcements/report';
import type { AnnouncementRecipientView, RecipientFilter } from '@/lib/announcements/types';
import { ApiRequestError } from '@/lib/api-error';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The light author report: how many were reached, read and confirmed, the people still to answer (names stay in memory
 * and are dropped after the screen closes), and "Remind those who have not confirmed".
 */
export default function ReportScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const format = useFormatInstant();
  const queryClient = useQueryClient();
  const { schoolId, announcementId } = useLocalSearchParams<{ schoolId: string; announcementId: string }>();
  const view = useAnnouncementQuery(schoolId, announcementId);
  const report = useReport(schoolId, announcementId);
  const asksAck = report.data?.requiresAcknowledgement ?? view.data?.requiresAcknowledgement ?? false;
  const filters = recipientFilters(asksAck);
  const [picked, setPicked] = useState<RecipientFilter | null>(null);
  const filter = picked && filters.includes(picked) ? picked : filters[0]!;
  const list = useRecipients(schoolId, announcementId, filter);
  const [reminding, setReminding] = useState(false);
  const [remindProblem, setRemindProblem] = useState<unknown>(null);
  const [reminded, setReminded] = useState<number | null>(null);

  async function doRemind() {
    setReminding(true);
    setRemindProblem(null);
    setReminded(null);
    try {
      const answer = await remind(schoolId, announcementId);
      setReminded(answer.targeted);
      void queryClient.invalidateQueries({ queryKey: reportKey(schoolId, announcementId) });
    } catch (error) {
      setRemindProblem(error);
    } finally {
      setReminding(false);
    }
  }

  function askRemind() {
    Alert.alert(t('mobile.announcements.report.remindConfirmTitle'), t('mobile.announcements.report.remindConfirmBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      { text: t('mobile.announcements.report.remindConfirm'), onPress: () => void doRemind() },
    ]);
  }

  const data = report.data;
  const canRemind = !!data && data.requiresAcknowledgement && data.status === 'PUBLISHED' && (view.data?.can.remind ?? false);
  const kind = remindProblem ? remindProblemKind(remindProblem) : null;

  const header = (
    <View style={{ gap: spacing[4], marginBottom: spacing[3] }}>
      {view.data ? (
        <Text variant="heading" numberOfLines={3}>
          {view.data.title}
        </Text>
      ) : null}
      {report.isPending ? <ActivityIndicator color={colors.primary} /> : null}
      {report.isError ? (
        <ProblemBanner error={report.error} fallbackTitle={t('mobile.announcements.report.failedTitle')} fallbackBody={t('mobile.announcements.report.failedBody')} onRetry={() => void report.refetch()} />
      ) : null}
      {data ? (
        <Card>
          <Meter label={t('mobile.announcements.report.delivered')} value={data.delivered} of={data.recipients} />
          <Meter label={t('mobile.announcements.report.read')} value={data.read} of={data.recipients} />
          {data.requiresAcknowledgement ? <Meter label={t('mobile.announcements.report.confirmed')} value={data.acknowledged} of={data.recipients} /> : null}
          {!data.deliveryComplete && data.status === 'PUBLISHED' ? <Text variant="caption">{t('mobile.announcements.report.deliveryRunning')}</Text> : null}
          {data.failed > 0 ? <Text variant="caption">{t('mobile.announcements.report.failed', { count: data.failed })}</Text> : null}
          {data.lateReaders > 0 ? <Text variant="caption">{t('mobile.announcements.report.lateReaders', { count: data.lateReaders })}</Text> : null}
          {data.reminders > 0 ? (
            <Text variant="caption">{t('mobile.announcements.report.reminders', { count: data.reminders, when: data.lastReminderAt ? format(data.lastReminderAt) : '' })}</Text>
          ) : null}
        </Card>
      ) : null}

      {canRemind ? (
        <View style={{ gap: spacing[2] }}>
          <Button label={t('mobile.announcements.report.remind')} loading={reminding} onPress={askRemind} />
          <Text variant="caption">{t('mobile.announcements.report.remindHint')}</Text>
          {reminded !== null ? (
            <Text accessibilityLiveRegion="polite" style={{ color: colors.success }}>
              {t('mobile.announcements.report.reminded', { count: reminded })}
            </Text>
          ) : null}
          {remindProblem ? (
            <View style={{ gap: spacing[2] }}>
              <ProblemBanner error={remindProblem} fallbackTitle={t('mobile.announcements.report.remindFailedTitle')} fallbackBody={t('mobile.announcements.report.remindFailedBody')} />
              {kind === 'too_soon' && remindProblem instanceof ApiRequestError && retryAfterHours(remindProblem.retryAfterSeconds) !== undefined ? (
                <Text variant="caption">{t('mobile.announcements.report.tryAgainIn', { hours: retryAfterHours(remindProblem.retryAfterSeconds)! })}</Text>
              ) : null}
            </View>
          ) : null}
        </View>
      ) : null}

      <Chips accessibilityLabel={t('mobile.announcements.report.filterLabel')} value={filter} onChange={setPicked} chips={filters.map((value) => ({ value, label: t(REPORT_FILTER[value]) }))} />
    </View>
  );

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={list.recipients}
        keyExtractor={(row) => row.userId}
        renderItem={({ item }) => <RecipientRow row={item} />}
        ListHeaderComponent={header}
        contentContainerStyle={{ flexGrow: 1, padding: spacing[5], gap: spacing[2] }}
        refreshControl={
          <RefreshControl
            refreshing={list.query.isRefetching && !list.query.isFetchingNextPage}
            onRefresh={() => void Promise.all([list.query.refetch(), report.refetch(), view.refetch()])}
            tintColor={colors.primary}
          />
        }
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (list.query.hasNextPage && !list.query.isFetchingNextPage) void list.query.fetchNextPage();
        }}
        ListEmptyComponent={
          list.query.isPending ? (
            <ActivityIndicator color={colors.primary} />
          ) : list.query.isError ? (
            <ProblemBanner error={list.query.error} fallbackTitle={t('mobile.announcements.report.failedTitle')} fallbackBody={t('mobile.announcements.report.failedBody')} onRetry={() => void list.query.refetch()} />
          ) : (
            <EmptyState icon={{ ios: 'checkmark.seal', android: 'verified' }} title={t('mobile.announcements.report.noneTitle')} body={t(REPORT_NONE[filter])} />
          )
        }
        ListFooterComponent={list.query.isFetchingNextPage ? <ActivityIndicator color={colors.primary} /> : undefined}
      />
    </SafeAreaView>
  );
}

function Meter({ label, value, of }: { label: string; value: number; of: number }) {
  const t = useTranslations();
  const { colors, radii } = useAppTheme();
  const share = percent(value, of);
  return (
    <View accessible accessibilityLabel={t('mobile.announcements.report.meter', { label, value, total: of, percent: share })} style={{ gap: 4 }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        <Text variant="label">{label}</Text>
        <Text variant="label">{t('mobile.announcements.report.meterValue', { value, total: of })}</Text>
      </View>
      <View style={{ height: 10, borderRadius: radii.full, backgroundColor: colors.muted, overflow: 'hidden' }}>
        <View style={{ width: `${share}%`, height: 10, backgroundColor: colors.primary }} />
      </View>
    </View>
  );
}

function RecipientRow({ row }: { row: AnnouncementRecipientView }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const read = !!row.readAt;
  const confirmed = !!row.acknowledgedAt;
  const via = row.viaStudentName ? t('mobile.announcements.report.via', { child: row.viaStudentName }) : null;
  const states = [read ? t('mobile.announcements.report.rowRead') : t('mobile.announcements.report.rowUnread'), confirmed ? t('mobile.announcements.report.rowConfirmed') : null].filter(Boolean).join(', ');
  return (
    <View
      accessible
      accessibilityLabel={[row.displayName, t(ROLE_LABEL[row.role]), via, states].filter(Boolean).join('. ')}
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[3], borderRadius: radii.lg, backgroundColor: colors.card, borderWidth: 1, borderColor: colors.border }}>
      <SymbolView
        name={confirmed ? { ios: 'checkmark.circle.fill', android: 'check_circle', web: 'check_circle' } : read ? { ios: 'eye.fill', android: 'visibility', web: 'visibility' } : { ios: 'circle', android: 'radio_button_unchecked', web: 'radio_button_unchecked' }}
        size={22}
        tintColor={confirmed ? colors.success : read ? colors.primary : colors.mutedForeground}
      />
      <View style={{ flex: 1 }}>
        <Text variant="label">{row.displayName}</Text>
        <Text variant="caption">{[t(ROLE_LABEL[row.role]), via].filter(Boolean).join(' · ')}</Text>
      </View>
      <Text variant="caption">{states}</Text>
    </View>
  );
}
