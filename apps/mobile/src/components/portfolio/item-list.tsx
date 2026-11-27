import { router } from 'expo-router';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';
import type { ReactElement, ReactNode } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, View } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { listState } from '@/lib/portfolio/model';
import type { PortfolioItem } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ProblemBanner } from '../problem-banner';
import { Button, EmptyState, Text } from '../ui';

import { ItemCard } from './item-card';
import { NeedsConnection } from './needs-connection';

export function openItem(item: Pick<PortfolioItem, 'id' | 'schoolId'>) {
  router.push({ pathname: '/portfolio/item/[itemId]', params: { itemId: item.id, schoolId: item.schoolId } });
}

export interface ItemListProps {
  query: UseInfiniteQueryResult<unknown, Error>;
  items: PortfolioItem[];
  /** Above the first item (filters, actions). */
  header?: ReactElement | null;
  /** Shown when there is nothing at all. */
  empty: { icon: { ios: string; android: string }; title: string; body: string; action?: ReactNode };
  /** Names the child on each card (the review queue and the guardian's combined view). */
  showStudent?: boolean;
  edges?: readonly Edge[];
  onRefresh?: () => Promise<unknown> | void;
}

/**
 * The shared list of the portfolio: a virtualised FlatList, pull to refresh, the next page near the end (keyset cursors,
 * bounded), an honest error with retry that keeps what is already shown, and "needs a connection" for a phone that is offline
 * (nothing is kept on the phone, so there is nothing else to show). Nothing here is persisted.
 */
export function ItemList({ query, items, header, empty, showStudent = false, edges = [], onRefresh }: ItemListProps) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const state = listState({ isPending: query.isPending, isError: query.isError, error: query.error, fetchStatus: query.fetchStatus, hasItems: items.length > 0 });

  async function refresh() {
    await Promise.all([query.refetch(), onRefresh?.()]);
  }

  return (
    <SafeAreaView edges={edges} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => <ItemCard item={item} showStudent={showStudent} onPress={openItem} />}
        ListHeaderComponent={header ?? undefined}
        contentContainerStyle={{ flexGrow: 1, paddingHorizontal: spacing[5], paddingVertical: spacing[4], gap: spacing[3] }}
        refreshControl={<RefreshControl refreshing={query.isRefetching && !query.isFetchingNextPage} onRefresh={() => void refresh()} tintColor={colors.primary} />}
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
        }}
        initialNumToRender={6}
        maxToRenderPerBatch={6}
        windowSize={7}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          state === 'loading' ? (
            <View style={{ paddingVertical: spacing[8] }}>
              <ActivityIndicator color={colors.primary} />
            </View>
          ) : state === 'offline' ? (
            <NeedsConnection onRetry={() => void query.refetch()} />
          ) : state === 'error' ? (
            <ProblemBanner
              error={query.error}
              fallbackTitle={t('mobile.portfolio.loadFailedTitle')}
              fallbackBody={t('mobile.portfolio.loadFailedBody')}
              onRetry={() => void query.refetch()}
            />
          ) : (
            <EmptyState icon={empty.icon} title={empty.title} body={empty.body}>
              {empty.action}
            </EmptyState>
          )
        }
        ListFooterComponent={
          query.isFetchingNextPage ? (
            <ActivityIndicator color={colors.primary} style={{ paddingVertical: spacing[4] }} />
          ) : query.isError && items.length > 0 ? (
            <View style={{ gap: spacing[2], paddingVertical: spacing[2] }}>
              <Text tone="muted">{t('mobile.portfolio.nextPageFailed')}</Text>
              <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void query.fetchNextPage()} />
            </View>
          ) : undefined
        }
      />
    </SafeAreaView>
  );
}
