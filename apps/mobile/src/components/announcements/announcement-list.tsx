import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import { router } from 'expo-router';
import type { ReactElement, ReactNode } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import type { AnnouncementCard } from '@/lib/announcements/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ProblemBanner } from '../problem-banner';
import { Button, EmptyState, Text } from '../ui';

import { AnnouncementRow } from './announcement-row';

export function openAnnouncement(card: Pick<AnnouncementCard, 'id' | 'schoolId'>) {
  router.push({ pathname: '/announcements/[announcementId]', params: { announcementId: card.id, schoolId: card.schoolId } });
}

/**
 * The list of announcements shared by the inbox and "my announcements": a virtualised FlatList, pull to refresh, the next page
 * when the end nears (keyset cursors, bounded), an honest error with retry that keeps what is already shown. Nothing is persisted.
 */
export function AnnouncementList({
  query,
  cards,
  header,
  empty,
  authorView = false,
  onRefresh,
}: {
  query: UseInfiniteQueryResult<unknown, Error>;
  cards: AnnouncementCard[];
  header?: ReactElement | null;
  empty: { icon: { ios: string; android: string }; title: string; body: string; action?: ReactNode };
  authorView?: boolean;
  onRefresh?: () => Promise<unknown> | void;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const showSchool = new Set(cards.map((card) => card.schoolId)).size > 1;

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={cards}
        keyExtractor={(card) => card.id}
        renderItem={({ item }) => <AnnouncementRow card={item} showSchool={showSchool} authorView={authorView} onPress={() => openAnnouncement(item)} />}
        ListHeaderComponent={header ?? undefined}
        contentContainerStyle={{ flexGrow: 1, paddingHorizontal: spacing[5], paddingVertical: spacing[4], gap: spacing[3] }}
        refreshControl={
          <RefreshControl
            refreshing={query.isRefetching && !query.isFetchingNextPage}
            onRefresh={() => void Promise.all([query.refetch(), onRefresh?.()])}
            tintColor={colors.primary}
          />
        }
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
        }}
        initialNumToRender={6}
        maxToRenderPerBatch={6}
        windowSize={7}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          query.isPending ? (
            <View style={{ paddingVertical: spacing[8] }}>
              <ActivityIndicator color={colors.primary} />
            </View>
          ) : query.isError ? (
            <ProblemBanner error={query.error} fallbackTitle={t('mobile.announcements.loadFailedTitle')} fallbackBody={t('mobile.announcements.loadFailedBody')} onRetry={() => void query.refetch()} />
          ) : (
            <EmptyState icon={empty.icon} title={empty.title} body={empty.body}>
              {empty.action}
            </EmptyState>
          )
        }
        ListFooterComponent={
          query.isFetchingNextPage ? (
            <ActivityIndicator color={colors.primary} style={{ paddingVertical: spacing[4] }} />
          ) : query.isError && cards.length > 0 ? (
            <View style={{ gap: spacing[2], paddingVertical: spacing[2] }}>
              <Text tone="muted">{t('mobile.announcements.nextPageFailed')}</Text>
              <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void query.fetchNextPage()} />
            </View>
          ) : cards.length > 0 && !query.hasNextPage ? (
            <Text variant="caption" style={{ textAlign: 'center', paddingVertical: spacing[3] }}>
              {t('mobile.announcements.endOfList')}
            </Text>
          ) : undefined
        }
      />
    </SafeAreaView>
  );
}
