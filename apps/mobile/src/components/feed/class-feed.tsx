import { useQueryClient } from '@tanstack/react-query';
import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import { type Edge } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { useSession } from '@/lib/session/session-provider';
import { classHeader, schoolHeader } from '@/lib/feed/header';
import { FEED_ROOT } from '@/lib/feed/keys';
import { useClassFeed, useClassStories, useFeedCapabilities, useSchoolFeed, useSchoolStories } from '@/lib/feed/queries';
import { DEFAULT_FEED_TAB, TAB_EMPTY_KEY, type FeedTab } from '@/lib/feed/tabs';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button } from '../ui';

import { FeedAnnouncementCard } from './announcement-card';
import { FeedHeader, type HeaderSwitcher } from './feed-header';
import { FeedList } from './feed-list';
import { FeedTabs } from './feed-tabs';
import { StoryStrip } from './story-strip';

/** What the band above a class or school feed shows (the names come from the caller's lists; the API is not asked again). */
export interface FeedChromeProps {
  schoolName?: string | undefined;
  /** A class feed: the class name. Ignored by the school feed. */
  className?: string | undefined;
  /** The title as a dropdown (another class, or the school's feed). */
  switcher?: HeaderSwitcher | undefined;
  /** Tab screens have no native header: the band reaches under the status bar. */
  insetTop?: boolean;
  edges?: readonly Edge[];
}

/**
 * A class's feed with its header band, tabs, announcement, stories and the actions the API says the caller has (post, story,
 * contribute, my posts, approvals). Used by the class feed route (teachers) and the student feed tab. Every tab is a filter the
 * API applies (Feed, Photos, Activities, Achievements): the posts shown are exactly what it answered.
 */
export function ClassFeed({ schoolId, classId, schoolName, className, switcher, insetTop = false, edges = ['bottom'] }: { schoolId: string; classId: string } & FeedChromeProps) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { preview } = useSession();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<FeedTab>(DEFAULT_FEED_TAB);
  const caps = useFeedCapabilities(schoolId, classId);
  const feed = useClassFeed(schoolId, classId, tab);
  const stories = useClassStories(schoolId, classId);
  const can = caps.data;
  const text = classHeader(schoolName, className);

  const compose = (kind: 'post' | 'story' | 'contribution') => router.push({ pathname: '/feed/compose', params: { kind, schoolId, classId } });

  const band = (
    <>
      <FeedHeader
        eyebrow={text.eyebrow}
        title={text.title ?? t('mobile.feed.classFeedTitle')}
        schoolName={schoolName}
        switcher={switcher}
        insetTop={insetTop}
        withAccount={!preview}
      />
      <FeedTabs value={tab} onChange={setTab} />
    </>
  );

  const header =
    tab === 'FEED' ? (
      <View style={{ gap: spacing[4] }}>
        <FeedAnnouncementCard schoolId={schoolId} />
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
      </View>
    ) : null;

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      band={band}
      statusStrip={insetTop}
      header={header}
      translationEnabled={can?.translationEnabled ?? false}
      timeZone={can?.timeZone}
      edges={edges}
      onRefresh={() => Promise.all([stories.refetch(), caps.refetch(), queryClient.invalidateQueries({ queryKey: [FEED_ROOT, 'stories'] })])}
      empty={
        tab === 'FEED'
          ? {
              icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' },
              title: t('mobile.feed.empty.classTitle'),
              body: can?.canPost ? t('mobile.feed.empty.classBodyTeacher') : t('mobile.feed.empty.classBody'),
            }
          : { icon: { ios: 'photo.on.rectangle.angled', android: 'photo_library' }, title: t(TAB_EMPTY_KEY[tab].title), body: t(TAB_EMPTY_KEY[tab].body) }
      }
    />
  );
}

/** The school-wide feed: header band, tabs, stories and posts for the members of the school, and the school-wide composer for those allowed. */
export function SchoolFeed({ schoolId, schoolName, switcher, insetTop = false, edges = ['bottom'] }: { schoolId: string } & Omit<FeedChromeProps, 'className'>) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const { preview } = useSession();
  const [tab, setTab] = useState<FeedTab>(DEFAULT_FEED_TAB);
  const caps = useFeedCapabilities(schoolId, null);
  const feed = useSchoolFeed(schoolId, tab);
  const stories = useSchoolStories(schoolId);
  const can = caps.data;
  const text = schoolHeader(schoolName);
  const compose = (kind: 'post' | 'story') => router.push({ pathname: '/feed/compose', params: { kind, schoolId } });

  const band = (
    <>
      <FeedHeader
        eyebrow={text.eyebrow}
        title={text.title ?? t('mobile.feed.schoolFeedTitle')}
        schoolName={schoolName}
        switcher={switcher}
        insetTop={insetTop}
        withAccount={!preview}
      />
      <FeedTabs value={tab} onChange={setTab} />
    </>
  );

  const header =
    tab === 'FEED' ? (
      <View style={{ gap: spacing[4] }}>
        <FeedAnnouncementCard schoolId={schoolId} />
        <StoryStrip stories={stories.data} loading={stories.isPending} onAdd={can?.canPostSchoolWide ? () => compose('story') : undefined} />
        {can?.canPostSchoolWide ? (
          <View style={{ gap: spacing[2] }}>
            <Button label={t('mobile.feed.newSchoolPost')} onPress={() => compose('post')} />
            <Button variant="ghost" label={t('mobile.feed.myPosts.title')} onPress={() => router.push({ pathname: '/feed/my-posts', params: { schoolId } })} />
          </View>
        ) : null}
      </View>
    ) : null;

  return (
    <FeedList
      query={feed.query}
      posts={feed.posts}
      band={band}
      statusStrip={insetTop}
      header={header}
      translationEnabled={can?.translationEnabled ?? false}
      timeZone={can?.timeZone}
      edges={edges}
      onRefresh={() => Promise.all([stories.refetch(), caps.refetch()])}
      empty={
        tab === 'FEED'
          ? { icon: { ios: 'building.2', android: 'school' }, title: t('mobile.feed.empty.schoolTitle'), body: t('mobile.feed.empty.schoolBody') }
          : { icon: { ios: 'building.2', android: 'school' }, title: t(TAB_EMPTY_KEY[tab].title), body: t(TAB_EMPTY_KEY[tab].body) }
      }
    />
  );
}
