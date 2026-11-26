import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { View } from 'react-native';
import { type Edge } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { FEED_ROOT } from '@/lib/feed/keys';
import { useClassFeed, useClassStories, useFeedCapabilities, useSchoolFeed, useSchoolStories } from '@/lib/feed/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text } from '../ui';

import { FeedList } from './feed-list';
import { StoryStrip } from './story-strip';

/**
 * A class's feed with its stories and the actions the API says the caller has (post, story, contribute, my posts, approvals).
 * Used by the class feed route (teachers) and the student feed tab. `before` goes above the stories (class chips).
 */
export function ClassFeed({ schoolId, classId, before, edges = ['bottom'] }: { schoolId: string; classId: string; before?: ReactNode; edges?: readonly Edge[] }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const queryClient = useQueryClient();
  const caps = useFeedCapabilities(schoolId, classId);
  const feed = useClassFeed(schoolId, classId);
  const stories = useClassStories(schoolId, classId);
  const can = caps.data;

  const compose = (kind: 'post' | 'story' | 'contribution') => router.push({ pathname: '/feed/compose', params: { kind, schoolId, classId } });

  const header = (
    <View style={{ gap: spacing[4] }}>
      {before}
      <StoryStrip stories={stories.data} loading={stories.isPending} onAdd={can?.canPost ? () => compose('story') : undefined} />
      <View style={{ gap: spacing[2] }}>
        {can?.canPost ? <Button label={t('mobile.feed.newPost')} onPress={() => compose('post')} /> : null}
        {can?.canContribute ? <Button label={t('mobile.feed.contribute.action')} variant="secondary" onPress={() => compose('contribution')} /> : null}
        {can?.canPost || can?.canContribute || can?.canModerate ? (
          <View style={{ flexDirection: 'row', gap: spacing[2] }}>
            <View style={{ flex: 1 }}>
              <Button variant="ghost" label={t('mobile.feed.myPosts.title')} onPress={() => router.push({ pathname: '/feed/my-posts', params: { schoolId, classId } })} />
            </View>
            {can?.canModerate ? (
              <View style={{ flex: 1 }}>
                <Button variant="ghost" label={t('mobile.feed.approvals.title')} onPress={() => router.push({ pathname: '/feed/approvals', params: { schoolId, classId } })} />
              </View>
            ) : null}
          </View>
        ) : null}
      </View>
      {feed.posts.length > 0 ? <Text variant="heading">{t('mobile.feed.posts')}</Text> : null}
    </View>
  );

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      header={header}
      translationEnabled={can?.translationEnabled ?? false}
      timeZone={can?.timeZone}
      edges={edges}
      onRefresh={() => Promise.all([stories.refetch(), caps.refetch(), queryClient.invalidateQueries({ queryKey: [FEED_ROOT, 'stories'] })])}
      empty={{
        icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' },
        title: t('mobile.feed.empty.classTitle'),
        body: can?.canPost ? t('mobile.feed.empty.classBodyTeacher') : t('mobile.feed.empty.classBody'),
      }}
    />
  );
}

/** The school-wide feed: stories and posts for the members of the school, and the school-wide composer for those allowed. */
export function SchoolFeed({ schoolId, before, edges = ['bottom'] }: { schoolId: string; before?: ReactNode; edges?: readonly Edge[] }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const caps = useFeedCapabilities(schoolId, null);
  const feed = useSchoolFeed(schoolId);
  const stories = useSchoolStories(schoolId);
  const can = caps.data;
  const compose = (kind: 'post' | 'story') => router.push({ pathname: '/feed/compose', params: { kind, schoolId } });

  const header = (
    <View style={{ gap: spacing[4] }}>
      {before}
      <StoryStrip stories={stories.data} loading={stories.isPending} onAdd={can?.canPostSchoolWide ? () => compose('story') : undefined} />
      {can?.canPostSchoolWide ? (
        <View style={{ gap: spacing[2] }}>
          <Button label={t('mobile.feed.newSchoolPost')} onPress={() => compose('post')} />
          <Button variant="ghost" label={t('mobile.feed.myPosts.title')} onPress={() => router.push({ pathname: '/feed/my-posts', params: { schoolId } })} />
        </View>
      ) : null}
      {feed.posts.length > 0 ? <Text variant="heading">{t('mobile.feed.posts')}</Text> : null}
    </View>
  );

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      header={header}
      translationEnabled={can?.translationEnabled ?? false}
      timeZone={can?.timeZone}
      edges={edges}
      onRefresh={() => Promise.all([stories.refetch(), caps.refetch()])}
      empty={{ icon: { ios: 'building.2', android: 'school' }, title: t('mobile.feed.empty.schoolTitle'), body: t('mobile.feed.empty.schoolBody') }}
    />
  );
}
