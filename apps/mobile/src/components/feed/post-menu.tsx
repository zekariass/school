import { router } from 'expo-router';
import { useCallback, useState, type ReactElement } from 'react';
import { Pressable } from 'react-native';
import { useTranslations } from 'use-intl';

import type { OverflowAction } from '@/lib/feed/post-model';
import type { PostView } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { ReportSheet } from '../moderation/report-sheet';
import { Sheet, Text } from '../ui';

import { usePostActions } from './use-post-actions';

export function openEditor(post: PostView) {
  router.push({ pathname: '/feed/compose', params: { kind: post.contribution ? 'contribution' : 'post', schoolId: post.schoolId, ...(post.classId ? { classId: post.classId } : {}), postId: post.id } });
}

/**
 * The "..." menu of a post card and the shared report sheet (`components/moderation`): `onMenu` goes to `<PostCard onMenu>`, `element` is rendered once by the
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
      <ReportSheet target={reporting ? { type: 'POST', id: reporting.id, schoolId: reporting.schoolId } : null} onClose={() => setReporting(null)} />
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
