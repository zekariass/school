import { useLocalSearchParams } from 'expo-router';
import { useMemo, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { FeedList } from '@/components/feed/feed-list';
import { PostCard } from '@/components/feed/post-card';
import { ReviewSheet } from '@/components/feed/review-sheet';
import { Button, EmptyState } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { useFeedCapabilities, usePendingContributions } from '@/lib/feed/queries';
import type { PostView } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The queue of family contributions waiting for a teacher. A notification names no school, so it is found from the
 * caller's teaching memberships (`/me`); with several schools the caller picks one.
 */
export default function ApprovalsScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { schoolId: schoolParam, classId } = useLocalSearchParams<{ schoolId?: string; classId?: string }>();
  const me = useMe();
  const schools = useMemo(() => {
    const found = new Map<string, string>();
    for (const membership of me.data?.memberships ?? []) if (membership.type === 'TEACHER') found.set(membership.schoolId, membership.schoolName);
    return [...found.entries()].map(([id, name]) => ({ id, name }));
  }, [me.data]);
  const [picked, setPicked] = useState<string | null>(null);
  const schoolId = schoolParam ?? (schools.length === 1 ? schools[0]!.id : picked);

  if (!schoolId) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background, padding: spacing[5], gap: spacing[4], justifyContent: 'center' }}>
        {me.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : schools.length === 0 ? (
          <EmptyState icon={{ ios: 'checkmark.seal', android: 'verified' }} title={t('mobile.feed.approvals.noneTitle')} body={t('mobile.feed.approvals.noneBody')} />
        ) : (
          <Chips accessibilityLabel={t('mobile.feed.approvals.pickSchool')} value={picked ?? ''} onChange={setPicked} chips={schools.map((school) => ({ value: school.id, label: school.name }))} />
        )}
      </View>
    );
  }
  return <Queue schoolId={schoolId} classId={classId ?? null} />;
}

function Queue({ schoolId, classId }: { schoolId: string; classId: string | null }) {
  const t = useTranslations();
  const feed = usePendingContributions(schoolId, classId);
  const caps = useFeedCapabilities(schoolId, classId);
  const [reviewing, setReviewing] = useState<PostView | null>(null);

  return (
    <>
      <FeedList
        query={feed.query}
        posts={feed.posts}
        edges={['bottom']}
        timeZone={caps.data?.timeZone}
        renderPost={(post) => (
          <View style={{ gap: 8 }}>
            <PostCard post={post} />
            <Button label={t('mobile.feed.approvals.review')} onPress={() => setReviewing(post)} />
          </View>
        )}
        empty={{ icon: { ios: 'checkmark.seal', android: 'verified' }, title: t('mobile.feed.approvals.emptyTitle'), body: t('mobile.feed.approvals.emptyBody') }}
      />
      <ReviewSheet post={reviewing} onClose={() => setReviewing(null)} />
    </>
  );
}
