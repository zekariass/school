import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Text } from '@/components/ui';
import { awardQueue, dismissAwardFailure, useAwardFailures, useQueuedAwards } from '@/lib/points/award-runtime';
import type { RosterEntry } from '@/lib/people/roster';
import type { PointCategory } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** "3 waiting to send": tap to try now. Hidden when nothing waits. */
export function QueueIndicator({ classId }: { classId: string }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const waiting = useQueuedAwards().filter((item) => item.classId === classId).length;
  if (waiting === 0) return null;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('mobile.points.queue.waiting', { count: waiting })}
      accessibilityHint={t('mobile.points.queue.retryHint')}
      onPress={() => void awardQueue.flush(true)}
      style={{
        alignSelf: 'flex-start',
        minHeight: minTouchTarget,
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[2],
        paddingHorizontal: spacing[4],
        borderRadius: radii.full,
        backgroundColor: colors.muted,
        borderWidth: 1,
        borderColor: colors.warning,
      }}>
      <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: colors.warning }} />
      <Text variant="label">{t('mobile.points.queue.waiting', { count: waiting })}</Text>
    </Pressable>
  );
}

/**
 * Awards the server refused for good (the student left the class, the skill was switched off) or that waited too
 * long. Says which student and skill, so the teacher can award again if they still mean it.
 */
export function FailureBanners({
  classId,
  students,
  categories,
}: {
  classId: string;
  students: readonly RosterEntry[];
  categories: readonly PointCategory[];
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const failures = useAwardFailures().filter((failure) => failure.item.classId === classId);
  if (failures.length === 0) return null;

  return (
    <View style={{ gap: spacing[2] }}>
      {failures.map((failure) => {
        const skill = categories.find((category) => category.id === failure.item.categoryId)?.name ?? t('mobile.points.queue.unknownSkill');
        const who = failure.item.wholeClass
          ? t('mobile.points.queue.wholeClass')
          : failure.item.studentIds
              .slice(0, 3)
              .map((id) => students.find((student) => student.id === id)?.firstName ?? t('mobile.points.queue.unknownStudent'))
              .join(', ') + (failure.item.studentIds.length > 3 ? ` +${failure.item.studentIds.length - 3}` : '');
        const reason =
          failure.kind === 'expired' ? t('mobile.points.queue.expiredBody') : failure.detail ?? t('mobile.points.queue.rejectedBody');
        return (
          <View
            key={failure.id}
            accessibilityRole="alert"
            style={{
              gap: spacing[1],
              padding: spacing[4],
              borderRadius: radii.xl,
              borderWidth: 1,
              borderColor: colors.destructive,
              backgroundColor: colors.muted,
            }}>
            <Text variant="label" tone="destructive">
              {t('mobile.points.queue.failedTitle', { skill, students: who })}
            </Text>
            <Text>{reason}</Text>
            <Button
              variant="ghost"
              fullWidth={false}
              label={t('common.done')}
              onPress={() => dismissAwardFailure(failure.id)}
            />
          </View>
        );
      })}
    </View>
  );
}
