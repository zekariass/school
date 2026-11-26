import { useQueries } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Chips } from '@/components/feed/chips';
import { FeedList } from '@/components/feed/feed-list';
import { StoryStrip } from '@/components/feed/story-strip';
import { TabHeader } from '@/components/tab-header';
import { Button, EmptyState, Sheet, Text } from '@/components/ui';
import { useMyChildren } from '@/lib/family/queries';
import { FEED_GC_MS, capabilitiesKey } from '@/lib/feed/keys';
import { useHomeFeed, useHomeStories } from '@/lib/feed/queries';
import { fetchCapabilities } from '@/lib/feed/requests';
import { useSession } from '@/lib/session/session-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * The parent's home feed: what the classes and the school of their children shared, across children and schools, with a child
 * filter and the stories on top. The API shows only what each link allows and hides photos whose consent is not granted
 * right now; a family contribution goes to a teacher first ("waiting for the teacher").
 */
export default function GuardianFeedTab() {
  const t = useTranslations();
  const { preview } = useSession();
  if (preview) {
    return <EmptyState icon={{ ios: 'photo.on.rectangle.angled', android: 'photo_library' }} title={t('mobile.feed.tab')} body={t('mobile.feed.empty.homeBody')} />;
  }
  return <HomeFeed />;
}

type Target = { schoolId: string; classId: string; label: string };

function HomeFeed() {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const children = useMyChildren();
  const [child, setChild] = useState<string>('all');
  const studentId = child === 'all' ? null : child;
  const feed = useHomeFeed(studentId);
  const stories = useHomeStories(studentId);

  const kids = useMemo(() => children.data?.children ?? [], [children.data]);
  const targets: Target[] = useMemo(() => {
    const found = new Map<string, Target>();
    for (const kid of kids) {
      if (studentId && kid.studentId !== studentId) continue;
      for (const school of kid.schools) {
        for (const cls of school.classes) found.set(cls.classId, { schoolId: school.schoolId, classId: cls.classId, label: `${cls.name} · ${school.schoolName}` });
      }
    }
    return [...found.values()];
  }, [kids, studentId]);

  const capabilities = useQueries({
    queries: targets.map((target) => ({
      queryKey: capabilitiesKey(target.schoolId, target.classId),
      queryFn: () => fetchCapabilities(target.schoolId, target.classId),
      staleTime: 2 * 60_000,
      gcTime: FEED_GC_MS,
      retry: false,
    })),
  });
  const canShare = targets.filter((_, index) => capabilities[index]?.data?.canContribute);

  const header = (
    <View style={{ gap: spacing[4] }}>
      <TabHeader />
      <Text variant="title">{t('mobile.feed.tab')}</Text>
      <StoryStrip stories={stories.data} loading={stories.isPending} />
      {kids.length > 1 ? (
        <Chips
          accessibilityLabel={t('mobile.feed.childFilter')}
          value={child}
          onChange={setChild}
          chips={[{ value: 'all', label: t('mobile.feed.allChildren') }, ...kids.map((kid) => ({ value: kid.studentId, label: kid.givenName }))]}
        />
      ) : null}
      {canShare.length > 0 ? <ShareButton targets={canShare} /> : null}
    </View>
  );

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      header={header}
      edges={['top']}
      onRefresh={() => Promise.all([stories.refetch(), children.refetch()])}
      empty={{ icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' }, title: t('mobile.feed.empty.homeTitle'), body: t('mobile.feed.empty.homeBody') }}
    />
  );
}

/** "Share with the class": one class goes straight to the composer; with several the parent picks which. */
function ShareButton({ targets }: { targets: Target[] }) {
  const t = useTranslations();
  const { colors, minTouchTarget, radii } = useAppTheme();
  const [open, setOpen] = useState(false);
  const go = (target: Target) => {
    setOpen(false);
    router.push({ pathname: '/feed/compose', params: { kind: 'contribution', schoolId: target.schoolId, classId: target.classId } });
  };
  return (
    <>
      <Button variant="secondary" label={t('mobile.feed.contribute.action')} onPress={() => (targets.length === 1 ? go(targets[0]!) : setOpen(true))} />
      <Sheet visible={open} onClose={() => setOpen(false)} title={t('mobile.feed.contribute.pickClass')}>
        {targets.map((target) => (
          <Pressable
            key={target.classId}
            accessibilityRole="button"
            accessibilityLabel={target.label}
            onPress={() => go(target)}
            style={({ pressed }) => ({ minHeight: minTouchTarget, justifyContent: 'center', paddingHorizontal: 12, borderRadius: radii.lg, backgroundColor: pressed ? colors.muted : 'transparent' })}>
            <Text variant="label">{target.label}</Text>
          </Pressable>
        ))}
      </Sheet>
    </>
  );
}
