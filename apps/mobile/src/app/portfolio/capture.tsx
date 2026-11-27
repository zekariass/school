import { router, useLocalSearchParams } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { NeedsConnection } from '@/components/portfolio/needs-connection';
import { ProblemBanner } from '@/components/problem-banner';
import { RosterGrid } from '@/components/roster-grid';
import { Button, EmptyState, Screen, Text } from '@/components/ui';
import { useMyClass, useRoster } from '@/lib/people/queries';
import { needsConnection } from '@/lib/portfolio/model';
import { usePortfolioCapabilities } from '@/lib/portfolio/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/**
 * Quick capture (spec 126), step one: whose work is it? The class wall of the roster; a tap opens the composer for that
 * student. Only a teacher the API lists for this class (`captureClassIds`) gets further; the composer asks again.
 */
export default function CapturePicker() {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const { classId } = useLocalSearchParams<{ classId: string }>();
  const { cls, query: mine } = useMyClass(classId);
  const roster = useRoster(cls?.schoolId, classId);
  const caps = usePortfolioCapabilities(cls?.schoolId);
  const allowed = !!classId && caps.data?.captureClassIds.includes(classId);

  return (
    <Screen scroll edges={['bottom']} contentStyle={{ gap: spacing[4] }}>
      {mine.isPending || caps.isPending || roster.isPending ? (
        <ActivityIndicator color={colors.primary} />
      ) : caps.isError ? (
        needsConnection(caps.error) ? (
          <NeedsConnection onRetry={() => void caps.refetch()} />
        ) : (
          <ProblemBanner error={caps.error} fallbackTitle={t('mobile.portfolio.loadFailedTitle')} fallbackBody={t('mobile.portfolio.loadFailedBody')} onRetry={() => void caps.refetch()} />
        )
      ) : !cls || !allowed ? (
        <EmptyState icon={{ ios: 'lock', android: 'lock' }} title={t('mobile.portfolio.compose.notAllowedTitle')} body={t('mobile.portfolio.compose.notAllowedCapture')}>
          <Button label={t('common.back')} onPress={() => router.back()} />
        </EmptyState>
      ) : (
        <>
          <Text>{t('mobile.portfolio.capture.pick')}</Text>
          <Text variant="caption">{t('mobile.portfolio.capture.note')}</Text>
          <View>
            <RosterGrid
              students={roster.data ?? []}
              onSelect={(student) => router.push({ pathname: '/portfolio/compose', params: { schoolId: cls.schoolId, classId: cls.id, studentId: student.id } })}
            />
          </View>
        </>
      )}
    </Screen>
  );
}
