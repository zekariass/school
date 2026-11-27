import { useQueryClient } from '@tanstack/react-query';
import { Stack, router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { ChildPointsView } from '@/components/points/child-points-view';
import { HomeAwardSheet } from '@/components/points/home-award-sheet';
import { ProblemBanner } from '@/components/problem-banner';
import { Button, Card, EmptyState, Screen, Text } from '@/components/ui';
import { useMyChildren } from '@/lib/family/queries';
import { POINTS_ROOT } from '@/lib/points/keys';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * A guardian's view of one child's points: school points and home points as two separate blocks (never summed),
 * badges, and, when the link allows it, a button to give home points.
 */
export default function ChildPoints() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { studentId } = useLocalSearchParams<{ studentId: string }>();
  const children = useMyChildren();
  const queryClient = useQueryClient();
  const [refreshing, setRefreshing] = useState(false);
  const [sheetSchool, setSheetSchool] = useState<string | null>(null);
  const [given, setGiven] = useState<string | null>(null);

  const child = children.data?.children.find((candidate) => candidate.studentId === studentId);

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([children.refetch(), queryClient.invalidateQueries({ queryKey: [POINTS_ROOT] })]);
    } finally {
      setRefreshing(false);
    }
  }

  if (!child) {
    return (
      <Screen edges={['bottom']}>
        {children.isPending ? (
          <ActivityIndicator color={colors.primary} />
        ) : children.isError ? (
          <ProblemBanner
            error={children.error}
            fallbackTitle={t('mobile.points.child.errorTitle')}
            fallbackBody={t('mobile.points.child.errorBody')}
            onRetry={() => void children.refetch()}
          />
        ) : (
          <EmptyState
            icon={{ ios: 'person.crop.circle.badge.questionmark', android: 'help' }}
            title={t('mobile.points.child.notFoundTitle')}
            body={t('mobile.points.child.notFoundBody')}>
            <Button label={t('common.back')} onPress={() => router.back()} />
          </EmptyState>
        )}
      </Screen>
    );
  }

  const canView = child.permissions.canViewAcademics;
  const canAward = child.permissions.canAwardPoints;
  const firstName = child.givenName;

  return (
    <Screen
      scroll
      edges={['bottom']}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} tintColor={colors.primary} />}
      contentStyle={{ gap: spacing[4] }}>
      <Stack.Screen options={{ title: firstName }} />
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[4] }}>
        <Avatar mediaId={child.avatar?.id} name={child.displayName} seed={child.studentId} config={child.avatarConfig} size={72} />
        <Text variant="title" style={{ flex: 1 }} numberOfLines={2}>
          {child.displayName}
        </Text>
      </View>

      {given ? (
        <Card accessibilityRole="alert" accessibilityLiveRegion="polite">
          <Text variant="label" tone="primary">
            {given}
          </Text>
        </Card>
      ) : null}

      {canView ? (
        <Button
          variant="secondary"
          label={t('mobile.portfolio.child.open', { name: firstName })}
          onPress={() => router.push({ pathname: '/portfolio/child/[studentId]', params: { studentId: child.studentId } })}
        />
      ) : null}

      <Button
        variant="ghost"
        label={t('mobile.attendance.guardian.open', { name: firstName })}
        onPress={() => router.push({ pathname: '/child/[studentId]/attendance', params: { studentId: child.studentId } })}
      />

      {child.schools.map((school) => {
        const homeButton = canAward ? (
          <Button
            variant="secondary"
            label={t('mobile.points.home.open', { name: firstName })}
            onPress={() => {
              setGiven(null);
              setSheetSchool(school.schoolId);
            }}
          />
        ) : null;
        return (
          <View key={school.schoolId} style={{ gap: spacing[3] }}>
            {child.schools.length > 1 ? <Text variant="heading">{school.schoolName}</Text> : null}
            {canView ? (
              <ChildPointsView
                schoolId={school.schoolId}
                studentId={child.studentId}
                schoolTitle={t('mobile.points.child.schoolPoints')}
                homeTitle={t('mobile.points.child.homePoints')}
                homeNote={t('mobile.points.child.homeNote')}
                homeAction={homeButton}
              />
            ) : (
              <Card>
                <Text>{t('mobile.points.child.noAccess')}</Text>
                {homeButton}
              </Card>
            )}
          </View>
        );
      })}

      {canAward && sheetSchool ? (
        <HomeAwardSheet
          visible
          schoolId={sheetSchool}
          studentId={child.studentId}
          childName={firstName}
          onClose={() => setSheetSchool(null)}
          onGiven={setGiven}
        />
      ) : null}
    </Screen>
  );
}
