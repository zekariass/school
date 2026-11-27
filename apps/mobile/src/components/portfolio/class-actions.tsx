import { router } from 'expo-router';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { usePortfolioCapabilities } from '@/lib/portfolio/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button } from '../ui';

/**
 * The portfolio buttons of a class, for a teacher: capture work (only for the classes the API lists in `captureClassIds`) and
 * the review queue (only when the API says the caller reviews). Nothing shows while the capabilities load or when they fail.
 */
export function ClassPortfolioButtons({ schoolId, classId }: { schoolId: string; classId: string }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const caps = usePortfolioCapabilities(schoolId);
  const canCapture = caps.data?.captureClassIds.includes(classId) === true;
  const reviewer = caps.data?.reviewer === true;
  if (!canCapture && !reviewer) return null;
  return (
    <View style={{ gap: spacing[3] }}>
      {canCapture ? (
        <Button variant="secondary" label={t('mobile.portfolio.capture.open')} onPress={() => router.push({ pathname: '/portfolio/capture', params: { classId } })} />
      ) : null}
      {reviewer ? (
        <Button variant="ghost" label={t('mobile.portfolio.queue.open')} onPress={() => router.push({ pathname: '/portfolio/queue/[classId]', params: { classId, schoolId } })} />
      ) : null}
    </View>
  );
}

/** "Add to portfolio" on a student's page in a class: straight to the composer for that student. */
export function CaptureForStudentButton({ schoolId, classId, studentId }: { schoolId: string; classId: string; studentId: string }) {
  const t = useTranslations();
  const caps = usePortfolioCapabilities(schoolId);
  if (caps.data?.captureClassIds.includes(classId) !== true) return null;
  return (
    <Button
      variant="secondary"
      label={t('mobile.portfolio.capture.forStudent')}
      onPress={() => router.push({ pathname: '/portfolio/compose', params: { schoolId, classId, studentId } })}
    />
  );
}
