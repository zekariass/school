import { useQueryClient } from '@tanstack/react-query';
import { randomUUID } from 'expo-crypto';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, FlatList, KeyboardAvoidingView, Platform, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { PostCard } from '@/components/feed/post-card';
import { usePostMenu } from '@/components/feed/post-menu';
import { ReportSheet } from '@/components/moderation/report-sheet';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState, Text, TextField } from '@/components/ui';
import { ApiRequestError, problemOf } from '@/lib/api-error';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { commentsKey } from '@/lib/feed/keys';
import { shouldLookUpSchools } from '@/lib/feed/notification-school';
import { authorLabel } from '@/lib/feed/post-model';
import { canReportComment } from '@/lib/moderation/visibility';
import { updatePostEverywhere, useComments, useFeedCapabilities, usePostAnywhere, usePostQuery } from '@/lib/feed/queries';
import { createComment, deleteComment, markPostSeen } from '@/lib/feed/requests';
import type { CommentView } from '@/lib/feed/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

const MAX_COMMENT = 1000;

/**
 * One post in full, with its comments. Opened from a card, or from a notification (`/feed/posts/{id}`: only the id is known, so
 * the caller's schools are asked in turn). A post that cannot be shown is explained in the API's words, never guessed.
 */
export default function PostScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { postId, schoolId: schoolParam } = useLocalSearchParams<{ postId: string; schoolId?: string }>();
  const direct = usePostQuery(schoolParam, postId);
  // `data.schoolId` of the notification is asked first; the caller's schools are asked in turn only without it or after a miss.
  const lookup = shouldLookUpSchools(schoolParam, direct.error);
  const anywhere = usePostAnywhere(postId, undefined, lookup);
  const post = direct.data ?? anywhere.data?.value;
  const caps = useFeedCapabilities(post?.schoolId, post?.classId ?? null);
  const { onMenu, element } = usePostMenu();

  useEffect(() => {
    if (post?.status === 'PUBLISHED') markPostSeen(post.schoolId, post.id).catch(() => undefined);
  }, [post?.schoolId, post?.id, post?.status]);

  if (!post) {
    const pending = lookup ? anywhere.isPending || anywhere.fetchStatus === 'idle' : direct.isPending;
    const failure = lookup ? anywhere.error : direct.error;
    const missing = failure instanceof ApiRequestError && failure.status === 404;
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' }}>
        {pending && !failure ? (
          <ActivityIndicator color={colors.primary} />
        ) : failure && !missing ? (
          <ProblemBanner
            error={failure}
            fallbackTitle={t('mobile.feed.loadFailedTitle')}
            fallbackBody={t('mobile.feed.loadFailedBody')}
            onRetry={() => void (lookup ? anywhere.refetch() : direct.refetch())}
          />
        ) : (
          <EmptyState icon={{ ios: 'photo.badge.exclamationmark', android: 'image_not_supported' }} title={t('mobile.feed.post.notFoundTitle')} body={t('mobile.feed.post.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))} />
          </EmptyState>
        )}
      </View>
    );
  }

  return (
    <SafeAreaView edges={['bottom']} style={{ flex: 1, backgroundColor: colors.background }}>
      <Stack.Screen options={{ title: t('mobile.feed.postTitle') }} />
      <CommentsList
        post={post}
        translationEnabled={caps.data?.translationEnabled ?? false}
        timeZone={caps.data?.timeZone}
        onMenu={onMenu}
      />
      {element}
    </SafeAreaView>
  );
}

function CommentsList({
  post,
  translationEnabled,
  timeZone,
  onMenu,
}: {
  post: NonNullable<ReturnType<typeof usePostQuery>['data']>;
  translationEnabled: boolean;
  timeZone: string | undefined;
  onMenu: ReturnType<typeof usePostMenu>['onMenu'];
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { locale } = usePreferences();
  const queryClient = useQueryClient();
  const { query, comments } = useComments(post.schoolId, post.id);

  const [draft, setDraft] = useState('');
  // The comment being reported (its id only): the shared report sheet.
  const [reporting, setReporting] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [problem, setProblem] = useState<{ message: string; offline: boolean } | null>(null);
  const attempt = useRef<{ text: string; key: string } | null>(null);

  async function send() {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    setProblem(null);
    // The same text after a dropped connection keeps its key (the comment may already be there); anything else starts a new one.
    attempt.current = attempt.current?.text === text ? attempt.current : { text, key: randomUUID() };
    try {
      await createComment(post.schoolId, post.id, text, locale, { idempotencyKey: attempt.current.key });
      attempt.current = null;
      setDraft('');
      updatePostEverywhere(queryClient, post.id, (current) => ({ ...current, commentCount: (current.commentCount ?? 0) + 1 }));
      await queryClient.invalidateQueries({ queryKey: commentsKey(post.schoolId, post.id) });
    } catch (error) {
      if (error instanceof ApiRequestError && error.status !== undefined && error.status < 500) attempt.current = null;
      setProblem({ message: problemOf(error)?.detail ?? t('mobile.feed.comments.offline'), offline: !problemOf(error) });
    } finally {
      setSending(false);
    }
  }

  const header = (
    <View style={{ gap: spacing[4], paddingBottom: spacing[2] }}>
      <PostCard post={post} mode="detail" translationEnabled={translationEnabled} timeZone={timeZone} onMenu={onMenu} />
      {post.status === 'PUBLISHED' ? <Text variant="heading">{t('mobile.feed.comments.title')}</Text> : null}
    </View>
  );

  return (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
      <FlatList
        data={post.status === 'PUBLISHED' ? comments : []}
        keyExtractor={(comment) => comment.id}
        renderItem={({ item }) => <CommentRow comment={item} post={post} onReport={() => setReporting(item.id)} />}
        ListHeaderComponent={header}
        contentContainerStyle={{ padding: spacing[5], gap: spacing[3] }}
        onEndReachedThreshold={0.5}
        onEndReached={() => {
          if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
        }}
        keyboardShouldPersistTaps="handled"
        ListEmptyComponent={
          post.status !== 'PUBLISHED' ? undefined : query.isPending ? (
            <ActivityIndicator color={colors.primary} />
          ) : query.isError ? (
            <ProblemBanner error={query.error} fallbackTitle={t('mobile.feed.loadFailedTitle')} fallbackBody={t('mobile.feed.loadFailedBody')} onRetry={() => void query.refetch()} />
          ) : (
            <Text tone="muted">{post.commentsEnabled ? t('mobile.feed.comments.none') : t('mobile.feed.commentsOff')}</Text>
          )
        }
      />
      {post.status === 'PUBLISHED' && post.commentsEnabled ? (
        post.rights.canComment ? (
          <View style={{ padding: spacing[4], gap: spacing[2], borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.background }}>
            <TextField
              label={t('mobile.feed.comments.write')}
              value={draft}
              onChangeText={setDraft}
              multiline
              maxLength={MAX_COMMENT}
              placeholder={t('mobile.feed.comments.placeholder')}
              hint={t('mobile.feed.comments.hint')}
              style={{ maxHeight: 120 }}
            />
            {problem ? (
              <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
                {problem.message}
              </Text>
            ) : null}
            <Button label={problem?.offline ? t('common.retry') : t('mobile.feed.comments.send')} loading={sending} disabled={!draft.trim()} onPress={() => void send()} />
          </View>
        ) : (
          <View style={{ padding: spacing[4] }}>
            <Text variant="caption">{t('mobile.feed.comments.notAllowed')}</Text>
          </View>
        )
      ) : null}
      <ReportSheet target={reporting ? { type: 'POST_COMMENT', id: reporting, schoolId: post.schoolId } : null} onClose={() => setReporting(null)} />
    </KeyboardAvoidingView>
  );
}

function CommentRow({ comment, post, onReport }: { comment: CommentView; post: { schoolId: string; id: string }; onReport: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const format = useFormatInstant();
  const queryClient = useQueryClient();
  const label = authorLabel(comment.author);
  const author = label.kind === 'name' ? label.name : t(`mobile.feed.author.${label.role}`);

  function remove() {
    Alert.alert(t('mobile.feed.comments.deleteTitle'), t('mobile.feed.comments.deleteBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('mobile.feed.menu.delete'),
        style: 'destructive',
        onPress: () => {
          deleteComment(post.schoolId, post.id, comment.id)
            .then(async () => {
              updatePostEverywhere(queryClient, post.id, (current) => ({ ...current, commentCount: Math.max(0, (current.commentCount ?? 0) - 1) }));
              await queryClient.invalidateQueries({ queryKey: commentsKey(post.schoolId, post.id) });
            })
            .catch((error) => Alert.alert(problemOf(error)?.title ?? t('mobile.feed.actionFailed'), problemOf(error)?.detail ?? t('mobile.feed.actionFailedBody')));
        },
      },
    ]);
  }

  return (
    <View style={{ padding: spacing[3], borderRadius: radii.xl, backgroundColor: colors.card, gap: 4 }}>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: spacing[2] }}>
        <Text variant="label" numberOfLines={1} style={{ flex: 1 }}>
          {author}
          {comment.mine ? ` · ${t('mobile.feed.you')}` : ''}
        </Text>
        <Text variant="caption">{format(comment.createdAt)}</Text>
      </View>
      {comment.status === 'HIDDEN' ? <Text variant="caption">{t('mobile.feed.comments.hiddenNote')}</Text> : null}
      <Text selectable>{comment.body}</Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {canReportComment(comment) ? <Button variant="ghost" fullWidth={false} label={t('mobile.moderation.action')} onPress={onReport} /> : null}
        {comment.canDelete ? <Button variant="ghost" fullWidth={false} label={t('mobile.feed.menu.delete')} onPress={remove} /> : null}
      </View>
    </View>
  );
}
