import { useLocalSearchParams, router } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { EventDetail } from '@/components/calendar/event-detail';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, EmptyState } from '@/components/ui';
import { isOccurrenceId } from '@/lib/calendar/occurrence';
import { isGone, problemKind } from '@/lib/calendar/problems';
import { useEvent } from '@/lib/calendar/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

function leave() {
  if (router.canGoBack()) router.back();
  else router.replace('/');
}

/**
 * One event. Opened from the calendar, or from a notification (`/calendar/events/{id}`, with the occurrence a reminder is about
 * when it has one). Events are global ids: the API answers 404 to anybody who may not see it (a parent meeting included), and
 * the screen says only that it is not available.
 */
export default function EventScreen() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { eventId, occurrenceId } = useLocalSearchParams<{ eventId: string; occurrenceId?: string; schoolId?: string }>();
  const query = useEvent(eventId);

  if (query.data) {
    return <EventDetail view={query.data} occurrenceId={isOccurrenceId(occurrenceId) ? occurrenceId : null} refresh={() => query.refetch()} />;
  }
  const centered = { flex: 1, backgroundColor: colors.background, padding: spacing[5], justifyContent: 'center' } as const;
  if (query.isError) {
    return (
      <View style={centered}>
        {isGone(query.error) ? (
          <EmptyState icon={{ ios: 'calendar.badge.exclamationmark', android: 'event_busy' }} title={t('mobile.calendar.detail.notFoundTitle')} body={t('mobile.calendar.detail.notFoundBody')}>
            <Button label={t('common.back')} onPress={leave} />
          </EmptyState>
        ) : (
          <ProblemBanner
            error={query.error}
            fallbackTitle={problemKind(query.error) === 'offline' ? t('mobile.calendar.offlineTitle') : t('mobile.calendar.loadFailedTitle')}
            fallbackBody={problemKind(query.error) === 'offline' ? t('mobile.calendar.offline') : t('mobile.calendar.loadFailedBody')}
            onRetry={() => void query.refetch()}
          />
        )}
      </View>
    );
  }
  return (
    <View style={centered}>
      <ActivityIndicator color={colors.primary} accessibilityLabel={t('common.loading')} />
    </View>
  );
}
