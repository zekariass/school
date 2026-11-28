import { router } from 'expo-router';
import { useCallback, type ReactElement, type ReactNode } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, View, type FlatListProps } from 'react-native';
import { SafeAreaView, useSafeAreaInsets, type Edge } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { markPostSeen } from '@/lib/feed/requests';
import type { PostView } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';
import type { UseInfiniteQueryResult } from '@tanstack/react-query';

import { ProblemBanner } from '../problem-banner';
import { Button, EmptyState, Text } from '../ui';

import { PostCard } from './post-card';
import { usePostMenu } from './post-menu';

/** Posts already reported as seen in this session (ids only, bounded): the API counts a viewer once anyway. */
const seen = new Set<string>();
const SEEN_LIMIT = 500;

const VIEWABILITY = { itemVisiblePercentThreshold: 60, minimumViewTime: 800 } as const;

export function openPost(post: Pick<PostView, 'id' | 'schoolId'>) {
  router.push({ pathname: '/feed/post/[postId]', params: { postId: post.id, schoolId: post.schoolId } });
}

export interface FeedListProps {
  query: UseInfiniteQueryResult<unknown, Error>;
  posts: PostView[];
  /** Full-width, above everything and scrolling with the list: the feed's header band and its tab strip. */
  band?: ReactElement | null;
  /** The band reaches under the status bar (tab screens): a solid strip of the band's colour keeps the clock readable. */
  statusStrip?: boolean;
  /** Above the first post (story strip, actions). */
  header?: ReactElement | null;
  /** Shown when there are no posts at all. */
  empty: { icon: { ios: string; android: string }; title: string; body: string; action?: ReactNode };
  translationEnabled?: boolean;
  timeZone?: string | undefined;
  edges?: readonly Edge[];
  /** Replaces the default card (the approval queue draws its own). */
  renderPost?: (post: PostView, menu: ReturnType<typeof usePostMenu>['onMenu']) => ReactElement;
  /** Extra pull-to-refresh work (stories, capabilities). */
  onRefresh?: () => Promise<unknown> | void;
}

/**
 * The shared feed list: a virtualised FlatList (a few cards rendered at a time), pull to refresh, the next page when the end
 * nears (keyset cursors, bounded), an honest error with retry that keeps what is already shown, and "seen" tracking of cards
 * that stay on screen. Nothing here is persisted.
 */
export function FeedList({ query, posts, band, statusStrip, header, empty, translationEnabled, timeZone, edges = [], renderPost, onRefresh }: FeedListProps) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const insets = useSafeAreaInsets();
  const { onMenu, element } = usePostMenu();

  // Must keep the same identity for the life of the list.
  const onViewable = useCallback<NonNullable<FlatListProps<PostView>['onViewableItemsChanged']>>(({ viewableItems }) => {
    for (const token of viewableItems) {
      const post = token.item;
      if (!post || post.status !== 'PUBLISHED' || seen.has(post.id)) continue;
      if (seen.size >= SEEN_LIMIT) seen.clear();
      seen.add(post.id);
      markPostSeen(post.schoolId, post.id).catch(() => seen.delete(post.id));
    }
  }, []);

  const refresh = useCallback(async () => {
    await Promise.all([query.refetch(), onRefresh?.()]);
  }, [onRefresh, query]);

  const loadingFirst = query.isPending;
  return (
    <SafeAreaView edges={edges} style={{ flex: 1, backgroundColor: colors.background }}>
      <FlatList
        data={posts}
        keyExtractor={(post) => post.id}
        renderItem={({ item }) =>
          renderPost ? (
            renderPost(item, onMenu)
          ) : (
            <PostCard post={item} translationEnabled={translationEnabled ?? false} timeZone={timeZone} onOpen={openPost} onMenu={onMenu} />
          )
        }
        ListHeaderComponent={
          band ? (
            <View style={{ gap: spacing[4], paddingBottom: spacing[4] }}>
              <View style={{ marginHorizontal: -spacing[5], marginTop: -spacing[4] }}>{band}</View>
              {header}
            </View>
          ) : header ? (
            <View style={{ paddingBottom: spacing[4] }}>{header}</View>
          ) : undefined
        }
        contentContainerStyle={{ flexGrow: 1, paddingHorizontal: spacing[5], paddingVertical: spacing[4] }}
        refreshControl={
          <RefreshControl
            refreshing={query.isRefetching && !query.isFetchingNextPage}
            onRefresh={() => void refresh()}
            tintColor={band ? colors.primaryForeground : colors.primary}
          />
        }
        onEndReachedThreshold={0.6}
        onEndReached={() => {
          if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
        }}
        onViewableItemsChanged={onViewable}
        viewabilityConfig={VIEWABILITY}
        initialNumToRender={3}
        maxToRenderPerBatch={4}
        windowSize={7}
        removeClippedSubviews
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          loadingFirst ? (
            <View style={{ paddingVertical: spacing[8] }}>
              <ActivityIndicator color={colors.primary} />
            </View>
          ) : query.isError ? (
            <ProblemBanner
              error={query.error}
              fallbackTitle={t('mobile.feed.loadFailedTitle')}
              fallbackBody={t('mobile.feed.loadFailedBody')}
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
          ) : query.isError && posts.length > 0 ? (
            <View style={{ gap: spacing[2], paddingVertical: spacing[2] }}>
              <Text tone="muted">{t('mobile.feed.nextPageFailed')}</Text>
              <Button variant="ghost" fullWidth={false} label={t('common.retry')} onPress={() => void query.fetchNextPage()} />
            </View>
          ) : posts.length > 0 && !query.hasNextPage ? (
            <Text variant="caption" style={{ textAlign: 'center', paddingVertical: spacing[3] }}>
              {t('mobile.feed.endOfFeed')}
            </Text>
          ) : undefined
        }
      />
      {statusStrip ? (
        <View pointerEvents="none" style={{ position: 'absolute', top: 0, left: 0, right: 0, height: insets.top, backgroundColor: colors.primary, zIndex: 2 }} />
      ) : null}
      {element}
    </SafeAreaView>
  );
}
