import { useQueryClient } from '@tanstack/react-query';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, Alert, RefreshControl, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { CommentsSection } from '@/components/portfolio/comments-section';
import { ConsentPanel } from '@/components/portfolio/consent-panels';
import { HistorySection } from '@/components/portfolio/history-section';
import { MediaGallery } from '@/components/portfolio/media-gallery';
import { NeedsConnection } from '@/components/portfolio/needs-connection';
import { ReviewSheet } from '@/components/portfolio/review-sheet';
import { StatusBadge } from '@/components/portfolio/status-badge';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Text } from '@/components/ui';
import { ApiRequestError } from '@/lib/api-error';
import { useFormatCivilDate, useFormatInstant } from '@/lib/calendar/use-format-instant';
import { shouldLookUpSchools } from '@/lib/feed/notification-school';
import { gateProblemOf, type GateProblem } from '@/lib/portfolio/gate';
import { ITEM_TYPE_LABEL, STATUS_HINT, VISIBILITY_LABEL } from '@/lib/portfolio/labels';
import { isLoweredByCap, needsConnection, withheldNotice } from '@/lib/portfolio/model';
import { refreshPortfolio, removeItemEverywhere, replaceItem, useItemAnywhere, useItemQuery } from '@/lib/portfolio/queries';
import { archiveItem, deleteItem, setFeatured, submitItem, unarchiveItem } from '@/lib/portfolio/requests';
import type { PortfolioItem } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * One item in full. Opened from a list or from a notification (`/portfolio/items/{id}`: the school comes with the notification,
 * and without it the caller's schools are asked in turn). Every button comes from the item's own `capabilities`, which the API
 * computed for this viewer; a status, an audience or a withheld picture is shown as the API sent it, never worked out here.
 */
export default function ItemScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { itemId, schoolId: schoolParam } = useLocalSearchParams<{ itemId: string; schoolId?: string }>();
  const direct = useItemQuery(schoolParam, itemId);
  const lookup = shouldLookUpSchools(schoolParam, direct.error);
  const anywhere = useItemAnywhere(itemId, undefined, lookup);
  const item = direct.data ?? anywhere.data?.value;

  if (!item) {
    const pending = lookup ? anywhere.isPending || anywhere.fetchStatus === 'idle' : direct.isPending;
    const paused = lookup ? anywhere.fetchStatus === 'paused' : direct.fetchStatus === 'paused';
    const failure = lookup ? anywhere.error : direct.error;
    const missing = failure instanceof ApiRequestError && (failure.status === 404 || failure.status === 403);
    const retry = () => void (lookup ? anywhere.refetch() : direct.refetch());
    return (
      <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        <Stack.Screen options={{ title: t('mobile.portfolio.item.title') }} />
        {paused || needsConnection(failure) ? (
          <NeedsConnection onRetry={retry} />
        ) : pending && !failure ? (
          <ActivityIndicator color={colors.primary} />
        ) : failure && !missing ? (
          <ProblemBanner error={failure} fallbackTitle={t('mobile.portfolio.loadFailedTitle')} fallbackBody={t('mobile.portfolio.loadFailedBody')} onRetry={retry} />
        ) : (
          <EmptyState icon={{ ios: 'photo.badge.exclamationmark', android: 'image_not_supported' }} title={t('mobile.portfolio.item.notFoundTitle')} body={t('mobile.portfolio.item.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
          </EmptyState>
        )}
      </SafeAreaView>
    );
  }
  return <ItemView item={item} />;
}

function ItemView({ item }: { item: PortfolioItem }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const formatDate = useFormatCivilDate();
  const formatInstant = useFormatInstant();
  const [reviewing, setReviewing] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [gate, setGate] = useState<GateProblem | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const can = item.capabilities;
  const title = item.title?.trim() || t('mobile.portfolio.untitled');
  const notice = withheldNotice(item);
  const showComments = can.canComment === true || (item.commentCount ?? 0) > 0;

  async function refresh() {
    setRefreshing(true);
    try {
      await queryClient.invalidateQueries({ queryKey: ['portfolio'] });
    } finally {
      setRefreshing(false);
    }
  }

  /** One state change: the API's answer replaces what every list holds; a consent-gate refusal is explained. */
  async function change(name: string, call: () => Promise<PortfolioItem>) {
    if (busy) return;
    setBusy(name);
    setProblem(null);
    setGate(null);
    try {
      const result = await call();
      replaceItem(queryClient, result);
      await refreshPortfolio(queryClient);
    } catch (error) {
      const found = gateProblemOf(error);
      if (found) setGate(found);
      else setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  function confirm(titleKey: Parameters<typeof t>[0], bodyKey: Parameters<typeof t>[0], actionKey: Parameters<typeof t>[0], run: () => void) {
    Alert.alert(t(titleKey), t(bodyKey), [
      { text: t('common.cancel'), style: 'cancel' },
      { text: t(actionKey), style: 'destructive', onPress: run },
    ]);
  }

  async function remove() {
    setBusy('delete');
    setProblem(null);
    try {
      await deleteItem(item.schoolId, item.id);
      removeItemEverywhere(queryClient, item.schoolId, item.id);
      await refreshPortfolio(queryClient);
      router.back();
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(null);
    }
  }

  const studentName = item.student.displayName?.trim();
  const date = item.workDate ? formatDate(item.workDate) : '';

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title: t('mobile.portfolio.item.title') }} />
      <ScrollView
        contentContainerStyle={{ padding: spacing[5], gap: spacing[4] }}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}>
        <View style={{ gap: spacing[2] }}>
          <Text variant="title">{title}</Text>
          {studentName ? <Text>{studentName}</Text> : null}
          <Text variant="caption">{[t(ITEM_TYPE_LABEL[item.itemType]), date].filter(Boolean).join(' · ')}</Text>
          <View style={{ flexDirection: 'row', gap: spacing[2], alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status={item.status} />
            {item.featured ? <Text variant="caption">{t('mobile.portfolio.featured')}</Text> : null}
          </View>
          <Text variant="caption">{t(STATUS_HINT[item.status])}</Text>
          <Text variant="caption">{t('mobile.portfolio.item.audience', { audience: t(VISIBILITY_LABEL[item.effectiveVisibility]) })}</Text>
          {isLoweredByCap(item) ? <Text variant="caption">{t('mobile.portfolio.item.loweredByCap', { chosen: t(VISIBILITY_LABEL[item.visibility]) })}</Text> : null}
          {item.submittedAt ? <Text variant="caption">{t('mobile.portfolio.item.submittedOn', { when: formatInstant(item.submittedAt) })}</Text> : null}
        </View>

        {item.reviewNote ? (
          <Card accessibilityLabel={t('mobile.portfolio.item.noteTitle')}>
            <Text variant="label">{t('mobile.portfolio.item.noteTitle')}</Text>
            <Text>{item.reviewNote}</Text>
          </Card>
        ) : null}

        {notice ? (
          <View accessibilityRole="alert" style={{ gap: spacing[1], padding: spacing[4], borderRadius: radii.xl, borderWidth: 2, borderColor: colors.warning, backgroundColor: colors.muted }}>
            <Text variant="label">{notice === 'hidden' ? t('mobile.portfolio.withheld.title') : t('mobile.portfolio.withheld.warningTitle')}</Text>
            <Text>{notice === 'hidden' ? t('mobile.portfolio.withheld.body') : t('mobile.portfolio.withheld.warningBody')}</Text>
          </View>
        ) : null}

        <MediaGallery media={item.media} title={title} />

        {item.textContent ? <Text>{item.textContent}</Text> : null}
        {item.description ? (
          <View style={{ gap: 2 }}>
            <Text variant="label">{t('mobile.portfolio.item.description')}</Text>
            <Text>{item.description}</Text>
          </View>
        ) : null}
        {item.reflection ? (
          <View style={{ gap: 2 }}>
            <Text variant="label">{t('mobile.portfolio.item.reflection')}</Text>
            <Text>{item.reflection}</Text>
          </View>
        ) : null}
        {item.taggedStudents && item.taggedStudents.length > 0 ? (
          <View style={{ gap: 2 }}>
            <Text variant="label">{t('mobile.portfolio.item.tagged')}</Text>
            <Text>{item.taggedStudents.map((tag) => tag.displayName?.trim()).filter(Boolean).join(', ') || t('mobile.portfolio.item.taggedCount', { count: item.taggedStudentCount ?? item.taggedStudents.length })}</Text>
          </View>
        ) : null}

        <ConsentPanel gate={gate?.kind === 'consent_missing' ? gate : null} known={new Map()} mode="review" />
        {gate?.kind === 'confirmation_required' ? (
          <View accessibilityRole="alert" style={{ gap: spacing[1] }}>
            <Text variant="label">{t('mobile.portfolio.gate.title')}</Text>
            <Text>{t('mobile.portfolio.gate.confirmationReview')}</Text>
          </View>
        ) : null}
        {problem ? (
          <ProblemBanner error={problem} fallbackTitle={t('mobile.portfolio.item.actionFailedTitle')} fallbackBody={t('mobile.portfolio.item.actionFailedBody')} />
        ) : null}

        <View style={{ gap: spacing[2] }}>
          {can.canEdit ? (
            <Button
              label={t('mobile.portfolio.item.edit')}
              onPress={() => router.push({ pathname: '/portfolio/compose', params: { schoolId: item.schoolId, itemId: item.id } })}
            />
          ) : null}
          {can.canSubmit ? (
            <Button variant="secondary" label={item.status === 'CHANGES_REQUESTED' ? t('mobile.portfolio.compose.sendAgain') : t('mobile.portfolio.compose.submit')} loading={busy === 'submit'} onPress={() => void change('submit', () => submitItem(item.schoolId, item.id))} />
          ) : null}
          {can.canReview ? <Button label={t('mobile.portfolio.review.open')} onPress={() => setReviewing(true)} /> : null}
          {can.canFeature ? (
            <Button variant="ghost" label={item.featured ? t('mobile.portfolio.item.unfeature') : t('mobile.portfolio.item.feature')} loading={busy === 'feature'} onPress={() => void change('feature', () => setFeatured(item.schoolId, item.id, !item.featured))} />
          ) : null}
          {can.canArchive ? (
            <Button
              variant="ghost"
              label={t('mobile.portfolio.item.archive')}
              loading={busy === 'archive'}
              onPress={() => confirm('mobile.portfolio.item.archiveTitle', 'mobile.portfolio.item.archiveBody', 'mobile.portfolio.item.archive', () => void change('archive', () => archiveItem(item.schoolId, item.id)))}
            />
          ) : null}
          {can.canUnarchive ? <Button variant="ghost" label={t('mobile.portfolio.item.unarchive')} loading={busy === 'unarchive'} onPress={() => void change('unarchive', () => unarchiveItem(item.schoolId, item.id))} /> : null}
          {can.canDelete ? (
            <Button
              variant="destructive"
              label={t('mobile.portfolio.item.delete')}
              loading={busy === 'delete'}
              onPress={() => confirm('mobile.portfolio.item.deleteTitle', 'mobile.portfolio.item.deleteBody', 'mobile.portfolio.item.delete', () => void remove())}
            />
          ) : null}
        </View>

        {showComments ? <CommentsSection item={item} /> : null}

        <HistorySection schoolId={item.schoolId} itemId={item.id} />
      </ScrollView>
      <ReviewSheet item={reviewing ? item : null} onClose={() => setReviewing(false)} />
    </SafeAreaView>
  );
}
