import type { ReactNode } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Card, Text } from '@/components/ui';
import { useStudentPoints } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { BadgesSection } from './badges-section';
import { PointsSection } from './points-section';

/**
 * A student's points as the student and their guardians see them: school points, then (when the API sends it)
 * home points as their own block, then badges. `homeAction` is the guardian's "give home points" button.
 */
export function ChildPointsView({
  schoolId,
  studentId,
  schoolTitle,
  homeTitle,
  homeNote,
  homeAction,
}: {
  schoolId: string | undefined;
  studentId: string | undefined;
  schoolTitle: string;
  homeTitle: string;
  homeNote: string;
  homeAction?: ReactNode;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const points = useStudentPoints(schoolId, studentId);

  if (points.isPending) return <ActivityIndicator color={colors.primary} />;
  if (points.isError || !points.data) {
    return (
      <ProblemBanner
        error={points.error}
        fallbackTitle={t('mobile.points.child.errorTitle')}
        fallbackBody={t('mobile.points.child.errorBody')}
        onRetry={() => void points.refetch()}
      />
    );
  }

  const data = points.data;
  return (
    <View style={{ gap: spacing[4] }}>
      <PointsSection title={schoolTitle} section={data.school} schoolId={schoolId} studentId={studentId} kind="SCHOOL" />
      {data.home ? (
        <PointsSection
          title={homeTitle}
          note={homeNote}
          section={data.home}
          schoolId={schoolId}
          studentId={studentId}
          kind="HOME"
          action={homeAction}
        />
      ) : homeAction ? (
        <Card>
          <Text variant="heading">{homeTitle}</Text>
          <Text tone="muted">{homeNote}</Text>
          {homeAction}
        </Card>
      ) : null}
      <BadgesSection badges={data.badges} />
    </View>
  );
}
