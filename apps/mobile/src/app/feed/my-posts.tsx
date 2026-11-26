import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { FeedList } from '@/components/feed/feed-list';
import { Button } from '@/components/ui';
import { useFeedCapabilities, useMyPosts } from '@/lib/feed/queries';
import type { PostStatus } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

type Filter = 'ALL' | Extract<PostStatus, 'DRAFT' | 'SCHEDULED' | 'PENDING_APPROVAL' | 'REJECTED' | 'PUBLISHED'>;
const FILTERS: readonly Filter[] = ['ALL', 'DRAFT', 'SCHEDULED', 'PENDING_APPROVAL', 'REJECTED', 'PUBLISHED'];

/**
 * "My posts": everything the caller wrote in a school in every state: drafts, scheduled posts (with their time in school time),
 * contributions waiting for a teacher, rejected ones with the reason, and published ones.
 */
export default function MyPostsScreen() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { schoolId, classId } = useLocalSearchParams<{ schoolId: string; classId?: string }>();
  const [filter, setFilter] = useState<Filter>('ALL');
  const feed = useMyPosts(schoolId, filter === 'ALL' ? null : filter, classId ?? null);
  const caps = useFeedCapabilities(schoolId, classId ?? null);

  const header = (
    <View style={{ gap: spacing[3] }}>
      <Chips
        accessibilityLabel={t('mobile.feed.myPosts.filter')}
        value={filter}
        onChange={setFilter}
        chips={FILTERS.map((value) => ({ value, label: t(`mobile.feed.myPosts.filters.${value}`) }))}
      />
    </View>
  );

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      header={header}
      timeZone={caps.data?.timeZone}
      edges={['bottom']}
      empty={{
        icon: { ios: 'square.and.pencil', android: 'edit_note' },
        title: t('mobile.feed.myPosts.emptyTitle'),
        body: t('mobile.feed.myPosts.emptyBody'),
        action: classId ? (
          <Button
            label={t('mobile.feed.newPost')}
            onPress={() => router.push({ pathname: '/feed/compose', params: { kind: caps.data?.canPost ? 'post' : 'contribution', schoolId, classId } })}
          />
        ) : undefined,
      }}
    />
  );
}
