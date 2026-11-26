import { useQueryClient } from '@tanstack/react-query';
import { router, useLocalSearchParams } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { LinkedText } from '@/components/announcements/linked-text';
import { TypeBadge } from '@/components/announcements/type-badge';
import { WithdrawSheet } from '@/components/announcements/withdraw-sheet';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { ackState, canAcknowledge } from '@/lib/announcements/ack';
import { AUDIENCE_LINE, MANAGE_STATUS, ROLE_LABEL } from '@/lib/announcements/labels';
import { showNewBadge } from '@/lib/announcements/inbox';
import { useAcknowledge, useAnnouncementAnywhere, useAnnouncementCapabilities, useAnnouncementQuery, useMarkRead, useReadAnnouncementNotifications, refreshAnnouncements, replaceAnnouncement } from '@/lib/announcements/queries';
import { deleteDraft, unschedule } from '@/lib/announcements/requests';
import { summarizeAudiences, translationFor, wasEdited } from '@/lib/announcements/summary';
import { isUrgentType, type AnnouncementView } from '@/lib/announcements/types';
import { ApiRequestError } from '@/lib/api-error';
import { formatInstant } from '@/lib/calendar/format-instant';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { shouldLookUpSchools } from '@/lib/feed/notification-school';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

function leave() {
  if (router.canGoBack()) router.back();
  else router.replace('/');
}

/**
 * One announcement. Opened from the inbox, or from a notification (`/announcements/{id}`, with `data.schoolId` when the push
 * had one; otherwise the caller's schools are asked in turn). Opening marks it read (once). The text is shown strictly as text.
 */
export default function AnnouncementScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { announcementId, schoolId: schoolParam } = useLocalSearchParams<{ announcementId: string; schoolId?: string }>();
  const direct = useAnnouncementQuery(schoolParam, announcementId);
  const lookup = shouldLookUpSchools(schoolParam, direct.error);
  const anywhere = useAnnouncementAnywhere(announcementId, lookup);
  const view = direct.data ?? anywhere.data?.value;
  const markRead = useMarkRead();
  const readNotifications = useReadAnnouncementNotifications();
  const opened = useRef<string | null>(null);

  // Marks it read once per opening (idempotent on the server). That does not touch the notifications of the announcement, so
  // they are marked read too (a tap on a notification already did; opening from the announcements list did not).
  useEffect(() => {
    if (!view || opened.current === view.id) return;
    opened.current = view.id;
    markRead(view).catch(() => undefined);
    readNotifications(view.id).catch(() => undefined);
  }, [view, markRead, readNotifications]);

  if (!view) {
    const pending = lookup ? anywhere.isPending || anywhere.fetchStatus === 'idle' : direct.isPending;
    const failure = lookup ? anywhere.error : direct.error;
    const missing = failure instanceof ApiRequestError && (failure.status === 404 || failure.status === 403);
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        {pending && !failure ? (
          <ActivityIndicator color={colors.primary} />
        ) : failure && !missing ? (
          <ProblemBanner
            error={failure}
            fallbackTitle={t('mobile.announcements.loadFailedTitle')}
            fallbackBody={t('mobile.announcements.loadFailedBody')}
            onRetry={() => void (lookup ? anywhere.refetch() : direct.refetch())}
          />
        ) : (
          <EmptyState icon={{ ios: 'megaphone', android: 'campaign' }} title={t('mobile.announcements.detail.notFoundTitle')} body={t('mobile.announcements.detail.notFoundBody')}>
            <Button label={t('common.back')} onPress={leave} />
          </EmptyState>
        )}
      </View>
    );
  }
  return <Detail view={view} refresh={() => (lookup ? anywhere.refetch() : direct.refetch())} />;
}

function Detail({ view, refresh }: { view: AnnouncementView; refresh: () => Promise<unknown> }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const format = useFormatInstant();
  const queryClient = useQueryClient();
  const acknowledge = useAcknowledge();
  const caps = useAnnouncementCapabilities(view.status === 'SCHEDULED' ? view.schoolId : undefined);
  const [refreshing, setRefreshing] = useState(false);
  const [showOriginal, setShowOriginal] = useState(false);
  const [ackProblem, setAckProblem] = useState<unknown>(null);
  const [ackBusy, setAckBusy] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [manageProblem, setManageProblem] = useState<unknown>(null);
  const [manageBusy, setManageBusy] = useState(false);

  const state = ackState(view);
  const withdrawn = view.status === 'WITHDRAWN';
  const urgent = isUrgentType(view.type);
  const translation = translationFor(view, locale);
  const shown = translation && !showOriginal ? translation : { title: view.title, body: view.body ?? '' };
  const scheduledAt = view.scheduledFor ? formatInstant(view.scheduledFor, { locale, calendar, timeZone: caps.data?.timeZone ?? 'UTC' }) : null;
  const lines = summarizeAudiences(view.audiences);
  const author = view.can.edit || view.can.withdraw || view.can.viewReport || view.can.delete || view.can.publish;

  async function onRefresh() {
    setRefreshing(true);
    try {
      await refresh();
    } finally {
      setRefreshing(false);
    }
  }

  async function onAcknowledge() {
    if (ackBusy) return;
    setAckBusy(true);
    setAckProblem(null);
    try {
      await acknowledge(view);
    } catch (error) {
      setAckProblem(error);
      // A withdrawn announcement (409) or one that changed: show the real state.
      void refresh();
    } finally {
      setAckBusy(false);
    }
  }

  async function manage(action: () => Promise<void>) {
    if (manageBusy) return;
    setManageBusy(true);
    setManageProblem(null);
    try {
      await action();
    } catch (error) {
      setManageProblem(error);
    } finally {
      setManageBusy(false);
    }
  }

  const editHref = { pathname: '/announcements/compose', params: { schoolId: view.schoolId, announcementId: view.id } } as const;

  return (
    <Screen scroll edges={['bottom']} refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void onRefresh()} tintColor={colors.primary} />} contentStyle={{ gap: spacing[4] }}>
      <View style={{ gap: spacing[3] }}>
        <TypeBadge type={view.type} />
        {showNewBadge(view) ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
            <SymbolView name={{ ios: 'circle.fill', android: 'fiber_manual_record', web: 'fiber_manual_record' }} size={12} tintColor={colors.primary} />
            <Text variant="caption" style={{ color: colors.primary }}>
              {t('mobile.announcements.status.new')}
            </Text>
          </View>
        ) : null}
        <Text variant="title" style={withdrawn ? { textDecorationLine: 'line-through' } : undefined} accessibilityRole="header">
          {shown.title}
        </Text>
        <Text variant="caption">
          {[
            view.schoolName,
            view.authorName ? t('mobile.announcements.from', { name: view.authorName }) : null,
            view.publishedAt ? t('mobile.announcements.detail.published', { when: format(view.publishedAt) }) : null,
          ]
            .filter(Boolean)
            .join(' · ')}
        </Text>
        {wasEdited(view) && view.editedAt && !withdrawn ? <Text variant="caption">{t('mobile.announcements.detail.edited', { when: format(view.editedAt) })}</Text> : null}
      </View>

      {urgent && !withdrawn ? (
        <View
          accessibilityRole="alert"
          style={{ flexDirection: 'row', gap: spacing[3], padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.destructive, backgroundColor: colors.card }}>
          <SymbolView name={{ ios: 'exclamationmark.triangle.fill', android: 'warning', web: 'warning' }} size={24} tintColor={colors.destructive} />
          <Text style={{ flex: 1 }}>{view.type === 'SAFETY' ? t('mobile.announcements.detail.safetyNote') : t('mobile.announcements.detail.urgentNote')}</Text>
        </View>
      ) : null}

      {withdrawn ? (
        <Card accessible accessibilityLabel={t('mobile.announcements.detail.withdrawnTitle')}>
          <View style={{ flexDirection: 'row', gap: spacing[3], alignItems: 'center' }}>
            <SymbolView name={{ ios: 'arrow.uturn.backward.circle.fill', android: 'undo', web: 'undo' }} size={26} tintColor={colors.mutedForeground} />
            <Text variant="heading" style={{ flex: 1 }}>
              {t('mobile.announcements.detail.withdrawnTitle')}
            </Text>
          </View>
          {view.withdrawnAt ? <Text tone="muted">{t('mobile.announcements.detail.withdrawnOn', { when: format(view.withdrawnAt) })}</Text> : null}
          {view.withdrawalReason ? <LinkedText text={t('mobile.announcements.detail.withdrawnReason', { reason: view.withdrawalReason })} /> : null}
          <Text tone="muted">{t('mobile.announcements.detail.withdrawnNote')}</Text>
        </Card>
      ) : (
        <View style={{ gap: spacing[2] }}>
          {translation ? (
            <View style={{ gap: spacing[1] }}>
              <Text variant="caption">{showOriginal ? t('mobile.announcements.detail.original') : t('mobile.announcements.detail.translatedBySchool')}</Text>
              <Button
                variant="ghost"
                fullWidth={false}
                label={showOriginal ? t('mobile.announcements.detail.showTranslation') : t('mobile.announcements.detail.showOriginal')}
                onPress={() => setShowOriginal((value) => !value)}
              />
            </View>
          ) : null}
          <LinkedText text={shown.body} selectable />
        </View>
      )}

      {state === 'needed' || state === 'renew' || state === 'done' ? (
        <Card>
          <View style={{ flexDirection: 'row', gap: spacing[3], alignItems: 'center' }}>
            <SymbolView
              name={state === 'done' ? { ios: 'checkmark.circle.fill', android: 'check_circle', web: 'check_circle' } : { ios: 'hand.raised.fill', android: 'front_hand', web: 'front_hand' }}
              size={26}
              tintColor={state === 'done' ? colors.success : colors.warning}
            />
            <Text variant="heading" style={{ flex: 1 }}>
              {state === 'done' ? t('mobile.announcements.ack.doneTitle') : state === 'renew' ? t('mobile.announcements.ack.renewTitle') : t('mobile.announcements.ack.title')}
            </Text>
          </View>
          <Text>
            {state === 'done'
              ? t('mobile.announcements.ack.doneBody', { when: view.mine.acknowledgedAt ? format(view.mine.acknowledgedAt) : '' })
              : state === 'renew'
                ? t('mobile.announcements.ack.renewBody')
                : t('mobile.announcements.ack.body')}
          </Text>
          {canAcknowledge(view) ? (
            <Button
              label={state === 'renew' ? t('mobile.announcements.ack.again') : t('mobile.announcements.ack.action')}
              loading={ackBusy}
              onPress={() => void onAcknowledge()}
            />
          ) : null}
          {ackProblem ? <ProblemBanner error={ackProblem} fallbackTitle={t('mobile.announcements.ack.failedTitle')} fallbackBody={t('mobile.announcements.ack.failedBody')} /> : null}
        </Card>
      ) : null}

      {author ? (
        <Card>
          <Text variant="heading">{t('mobile.announcements.manage.title')}</Text>
          <Text>{t(MANAGE_STATUS[view.status])}</Text>
          {view.status === 'SCHEDULED' && scheduledAt ? <Text tone="muted">{t('mobile.announcements.manage.scheduledFor', { when: scheduledAt, zone: caps.data?.timeZone ?? 'UTC' })}</Text> : null}
          {view.status === 'DRAFT' && view.scheduleErrorCode ? <Text tone="destructive">{t('mobile.announcements.manage.scheduleBlocked')}</Text> : null}
          {view.recipientCount !== undefined && view.status !== 'DRAFT' && view.status !== 'SCHEDULED' ? (
            <Text tone="muted">{t('mobile.announcements.manage.recipients', { count: view.recipientCount })}</Text>
          ) : null}
          {view.status === 'PUBLISHED' && view.deliveryComplete === false ? <Text tone="muted">{t('mobile.announcements.manage.deliveryRunning')}</Text> : null}
          {lines.map((line) => (
            <Text key={`${line.type}-${line.roles.join()}`} variant="caption">
              {t(AUDIENCE_LINE[line.type], { count: line.count, roles: line.roles.map((role) => t(ROLE_LABEL[role])).join(', ') })}
            </Text>
          ))}
          {manageProblem ? <ProblemBanner error={manageProblem} fallbackTitle={t('mobile.announcements.manage.failedTitle')} fallbackBody={t('mobile.announcements.manage.failedBody')} /> : null}
          {view.can.edit ? <Button variant="secondary" label={view.status === 'PUBLISHED' ? t('mobile.announcements.manage.editPublished') : t('mobile.announcements.manage.edit')} onPress={() => router.push(editHref)} /> : null}
          {view.can.viewReport ? (
            <Button variant="ghost" label={t('mobile.announcements.manage.report')} onPress={() => router.push({ pathname: '/announcements/report', params: { schoolId: view.schoolId, announcementId: view.id } })} />
          ) : null}
          {view.status === 'SCHEDULED' && view.can.schedule ? (
            <Button
              variant="ghost"
              label={t('mobile.announcements.manage.unschedule')}
              loading={manageBusy}
              onPress={() =>
                void manage(async () => {
                  replaceAnnouncement(queryClient, await unschedule(view.schoolId, view.id));
                  void refreshAnnouncements(queryClient);
                })
              }
            />
          ) : null}
          {view.can.withdraw ? <Button variant="destructive" label={t('mobile.announcements.manage.withdraw')} onPress={() => setWithdrawing(true)} /> : null}
          {view.can.delete ? (
            <Button
              variant="destructive"
              label={t('mobile.announcements.manage.delete')}
              loading={manageBusy}
              onPress={() =>
                Alert.alert(t('mobile.announcements.manage.deleteTitle'), t('mobile.announcements.manage.deleteBody'), [
                  { text: t('common.cancel'), style: 'cancel' },
                  {
                    text: t('mobile.announcements.manage.delete'),
                    style: 'destructive',
                    onPress: () =>
                      void manage(async () => {
                        await deleteDraft(view.schoolId, view.id);
                        void refreshAnnouncements(queryClient);
                        leave();
                      }),
                  },
                ])
              }
            />
          ) : null}
        </Card>
      ) : null}
      {view.can.withdraw ? <WithdrawSheet view={view} visible={withdrawing} onClose={() => setWithdrawing(false)} /> : null}
    </Screen>
  );
}
