import { router, useLocalSearchParams } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { StoryViewer } from '@/components/feed/story-viewer';
import { Button, EmptyState } from '@/components/ui';
import { ProblemBanner } from '@/components/problem-banner';
import { ApiRequestError } from '@/lib/api-error';
import { useStoryAnywhere, useStoryQuery } from '@/lib/feed/queries';
import { isLive } from '@/lib/feed/story-timing';
import { useClock } from '@/lib/feed/use-clock';
import { useAppTheme } from '@/lib/theme/theme-provider';

function leave() {
  if (router.canGoBack()) router.back();
  else router.replace('/');
}

/** One story opened from a notification (`/feed/stories/{id}`): the viewer, or the reason there is none (a story lasts 24 hours). */
export default function StoryScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { storyId, schoolId: schoolParam } = useLocalSearchParams<{ storyId: string; schoolId?: string }>();
  const now = useClock();
  const direct = useStoryQuery(schoolParam, storyId);
  const anywhere = useStoryAnywhere(schoolParam ? undefined : storyId);
  const story = direct.data ?? anywhere.data?.value;
  const failure = schoolParam ? direct.error : anywhere.error;
  const pending = schoolParam ? direct.isPending : anywhere.isPending || anywhere.fetchStatus === 'idle';

  const base = { flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' } as const;
  if (story && isLive(story, now)) {
    return (
      <View style={{ flex: 1, backgroundColor: colors.background }}>
        <StoryViewer stories={[story]} startIndex={0} onClose={leave} />
      </View>
    );
  }
  if (pending && !failure) {
    return (
      <View style={base}>
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }
  if (failure && !(failure instanceof ApiRequestError && failure.status === 404)) {
    return (
      <View style={base}>
        <ProblemBanner error={failure} fallbackTitle={t('mobile.feed.loadFailedTitle')} fallbackBody={t('mobile.feed.loadFailedBody')} onRetry={() => void (schoolParam ? direct.refetch() : anywhere.refetch())} />
        <Button variant="ghost" label={t('common.back')} onPress={leave} />
      </View>
    );
  }
  return (
    <View style={base}>
      <EmptyState icon={{ ios: 'clock.badge.xmark', android: 'timer_off' }} title={t('mobile.feed.story.goneTitle')} body={t('mobile.feed.story.goneBody')}>
        <Button label={t('common.back')} onPress={leave} />
      </EmptyState>
    </View>
  );
}
