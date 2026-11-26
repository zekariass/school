import { Stack } from 'expo-router';
import { useTranslations } from 'use-intl';

import { HeaderBack } from '@/components/header-back';
import { useStackHeader } from '@/components/use-stack-header';

/**
 * The class feed, the school feed, one post or story, the composer, "my posts" and the approval queue: a stack above the tabs
 * of teachers, parents and students alike (a notification can open any of them first, so every screen has its own back button).
 */
export default function FeedStack() {
  const t = useTranslations();
  const header = useStackHeader();
  return (
    <Stack screenOptions={{ ...header, title: '', headerLeft: () => <HeaderBack /> }}>
      <Stack.Screen name="class/[classId]" options={{ title: t('mobile.feed.classFeedTitle') }} />
      <Stack.Screen name="school/[schoolId]" options={{ title: t('mobile.feed.schoolFeedTitle') }} />
      <Stack.Screen name="post/[postId]" options={{ title: t('mobile.feed.postTitle') }} />
      <Stack.Screen name="story/[storyId]" options={{ title: t('mobile.feed.story.title'), headerShown: false, presentation: 'transparentModal', animation: 'fade' }} />
      <Stack.Screen name="compose" options={{ title: t('mobile.feed.compose.title'), presentation: 'modal', headerLeft: undefined }} />
      <Stack.Screen name="my-posts" options={{ title: t('mobile.feed.myPosts.title') }} />
      <Stack.Screen name="approvals" options={{ title: t('mobile.feed.approvals.title') }} />
    </Stack>
  );
}
