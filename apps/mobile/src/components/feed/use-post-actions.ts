import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { Alert } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { gateProblemOf } from '@/lib/feed/gate';
import type { OverflowAction } from '@/lib/feed/post-model';
import { refreshFeeds, removePostEverywhere, replacePost } from '@/lib/feed/queries';
import { archivePost, deletePost, hidePost, pinPost, publishPost, unhidePost, unschedulePost } from '@/lib/feed/requests';
import type { PostView } from '@/lib/feed/types';

/**
 * The actions of a post's menu, each asking for confirmation when it is hard to undo. Failures are shown as the API's own
 * localized `problem.detail`; a consent-gate refusal opens the editor instead, which explains it and keeps nothing lost.
 */
export function usePostActions({ onEdit, onReport }: { onEdit: (post: PostView) => void; onReport: (post: PostView) => void }) {
  const t = useTranslations();
  const queryClient = useQueryClient();

  const fail = useCallback(
    (error: unknown, post: PostView) => {
      if (gateProblemOf(error)) {
        Alert.alert(problemOf(error)?.title ?? t('mobile.feed.gate.title'), problemOf(error)?.detail ?? t('mobile.feed.gate.openEditor'), [
          { text: t('common.cancel'), style: 'cancel' },
          { text: t('mobile.feed.menu.edit'), onPress: () => onEdit(post) },
        ]);
        return;
      }
      Alert.alert(problemOf(error)?.title ?? t('mobile.feed.actionFailed'), problemOf(error)?.detail ?? t('mobile.feed.actionFailedBody'));
    },
    [onEdit, t],
  );

  const settle = useCallback(
    async (post: PostView, call: () => Promise<PostView>) => {
      try {
        replacePost(queryClient, await call());
        await refreshFeeds(queryClient);
      } catch (error) {
        fail(error, post);
      }
    },
    [fail, queryClient],
  );

  return useCallback(
    (action: OverflowAction, post: PostView) => {
      const { schoolId, id } = post;
      switch (action) {
        case 'edit':
          return onEdit(post);
        case 'report':
          return onReport(post);
        case 'publish':
          return void settle(post, () => publishPost(schoolId, id, false));
        case 'unschedule':
          return void settle(post, () => unschedulePost(schoolId, id));
        case 'pin':
          return void settle(post, () => pinPost(schoolId, id, true));
        case 'unpin':
          return void settle(post, () => pinPost(schoolId, id, false));
        case 'unhide':
          return void settle(post, () => unhidePost(schoolId, id, false));
        case 'archive':
          return confirm(t('common.cancel'), t('mobile.feed.archive.title'), t('mobile.feed.archive.body'), t('mobile.feed.menu.archive'), false, () =>
            void settle(post, () => archivePost(schoolId, id)),
          );
        case 'hide':
          return confirm(t('common.cancel'), t('mobile.feed.hide.title'), t('mobile.feed.hide.body'), t('mobile.feed.menu.hide'), true, () =>
            void settle(post, () => hidePost(schoolId, id, null)),
          );
        case 'delete':
          return confirm(t('common.cancel'), t('mobile.feed.delete.title'), t('mobile.feed.delete.body'), t('mobile.feed.menu.delete'), true, () => {
            void (async () => {
              try {
                await deletePost(schoolId, id);
                removePostEverywhere(queryClient, id);
                await refreshFeeds(queryClient);
              } catch (error) {
                fail(error, post);
              }
            })();
          });
      }
    },
    [fail, onEdit, onReport, queryClient, settle, t],
  );
}

function confirm(cancel: string, title: string, message: string, label: string, destructive: boolean, onConfirm: () => void) {
  Alert.alert(title, message, [
    { text: cancel, style: 'cancel' },
    { text: label, style: destructive ? 'destructive' : 'default', onPress: onConfirm },
  ]);
}
