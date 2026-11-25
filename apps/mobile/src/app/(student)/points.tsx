import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ChildPointsView } from '@/components/points/child-points-view';
import { ProblemBanner } from '@/components/problem-banner';
import { TabHeader } from '@/components/tab-header';
import { EmptyState, Screen, Text } from '@/components/ui';
import { useMe } from '@/lib/auth/use-me';
import { POINTS_ROOT } from '@/lib/points/keys';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** The student's own points: school points, home points (when a family member gave any), badges. */
export default function StudentPoints() {
  const t = useTranslations();
  const { spacing, colors } = useAppTheme();
  const me = useMe();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([me.refetch(), queryClient.invalidateQueries({ queryKey: [POINTS_ROOT] })]);
    } finally {
      setRefreshing(false);
    }
  }

  const memberships = (me.data?.memberships ?? []).filter(
    (membership) => membership.type === 'STUDENT' && membership.studentId,
  );

  return (
    <Screen
      scroll
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[4] }}>
      <TabHeader />
      <Text variant="title">{t('mobile.points.student.title')}</Text>

      {me.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : me.isError && !me.data ? (
        <ProblemBanner
          error={me.error}
          fallbackTitle={t('mobile.points.child.errorTitle')}
          fallbackBody={t('mobile.points.child.errorBody')}
          onRetry={() => void me.refetch()}
        />
      ) : memberships.length === 0 ? (
        <EmptyState
          icon={{ ios: 'trophy.fill', android: 'emoji_events' }}
          title={t('mobile.points.student.emptyTitle')}
          body={t('mobile.points.student.emptyBody')}
        />
      ) : (
        memberships.map((membership) => (
          <View key={membership.schoolId} style={{ gap: spacing[3] }}>
            {memberships.length > 1 ? <Text variant="heading">{membership.schoolName}</Text> : null}
            <ChildPointsView
              schoolId={membership.schoolId}
              studentId={membership.studentId}
              schoolTitle={t('mobile.points.student.schoolPoints')}
              homeTitle={t('mobile.points.student.homePoints')}
              homeNote={t('mobile.points.student.homeNote')}
            />
          </View>
        ))
      )}
    </Screen>
  );
}
