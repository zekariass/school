import { router } from 'expo-router';
import { useCallback, useState, type ReactElement } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import type { OverflowAction } from '@/lib/feed/post-model';
import { reportPost } from '@/lib/feed/requests';
import type { PostView, ReportReason } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Sheet, Text } from '../ui';

import { usePostActions } from './use-post-actions';

const REASONS: readonly ReportReason[] = ['INAPPROPRIATE', 'PRIVACY', 'SPAM', 'OTHER'];

export function openEditor(post: PostView) {
  router.push({ pathname: '/feed/compose', params: { kind: post.contribution ? 'contribution' : 'post', schoolId: post.schoolId, ...(post.classId ? { classId: post.classId } : {}), postId: post.id } });
}

/**
 * The "..." menu of a post card and the report sheet: `onMenu` goes to `<PostCard onMenu>`, `element` is rendered once by the
 * screen. The entries come from the post's own `rights` (`overflowActions`), never from the viewer's role.
 */
export function usePostMenu(): { onMenu: (post: PostView, actions: OverflowAction[]) => void; element: ReactElement } {
  const t = useTranslations();
  const [menu, setMenu] = useState<{ post: PostView; actions: OverflowAction[] } | null>(null);
  const [reporting, setReporting] = useState<PostView | null>(null);
  const run = usePostActions({ onEdit: openEditor, onReport: setReporting });
  const onMenu = useCallback((post: PostView, actions: OverflowAction[]) => setMenu({ post, actions }), []);

  const element = (
    <>
      <Sheet visible={!!menu} onClose={() => setMenu(null)} title={t('mobile.feed.moreActions')}>
        {menu?.actions.map((action) => (
          <MenuRow
            key={action}
            label={t(`mobile.feed.menu.${action}`)}
            destructive={action === 'delete' || action === 'hide'}
            onPress={() => {
              const target = menu.post;
              setMenu(null);
              run(action, target);
            }}
          />
        ))}
      </Sheet>
      <ReportSheet post={reporting} onClose={() => setReporting(null)} />
    </>
  );
  return { onMenu, element };
}

function MenuRow({ label, destructive, onPress }: { label: string; destructive: boolean; onPress: () => void }) {
  const { colors, minTouchTarget, radii } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => ({ minHeight: minTouchTarget, justifyContent: 'center', paddingHorizontal: 12, borderRadius: radii.lg, backgroundColor: pressed ? colors.muted : 'transparent' })}>
      <Text variant="label" tone={destructive ? 'destructive' : 'default'}>
        {label}
      </Text>
    </Pressable>
  );
}

function ReportSheet({ post, onClose }: { post: PostView | null; onClose: () => void }) {
  const t = useTranslations();
  const [state, setState] = useState<{ phase: 'choose' | 'sending' | 'done'; error?: string }>({ phase: 'choose' });

  function close() {
    setState({ phase: 'choose' });
    onClose();
  }

  async function send(reason: ReportReason) {
    if (!post) return;
    setState({ phase: 'sending' });
    try {
      await reportPost(post.schoolId, post.id, reason);
      setState({ phase: 'done' });
    } catch (error) {
      setState({ phase: 'choose', error: problemOf(error)?.detail ?? t('mobile.feed.report.failed') });
    }
  }

  return (
    <Sheet visible={!!post} onClose={close} title={t('mobile.feed.report.title')}>
      {state.phase === 'done' ? (
        <View style={{ gap: 12 }} accessibilityLiveRegion="polite">
          <Text>{t('mobile.feed.report.thanks')}</Text>
          <MenuRow label={t('common.done')} destructive={false} onPress={close} />
        </View>
      ) : (
        <View style={{ gap: 4 }}>
          <Text tone="muted">{t('mobile.feed.report.body')}</Text>
          {REASONS.map((reason) => (
            <MenuRow key={reason} label={t(`mobile.feed.report.reason.${reason}`)} destructive={false} onPress={() => void send(reason)} />
          ))}
          {state.phase === 'sending' ? <Text variant="caption">{t('common.loading')}</Text> : null}
          {state.error ? (
            <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
              {state.error}
            </Text>
          ) : null}
        </View>
      )}
    </Sheet>
  );
}
