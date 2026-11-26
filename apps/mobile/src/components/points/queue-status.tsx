import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Button, Text } from '@/components/ui';
import type { RosterEntry } from '@/lib/people/roster';
import { awardView } from '@/lib/points/award-view';
import type { PointCategory } from '@/lib/points/queries';
import { syncEngine, useAttentionOperations } from '@/lib/sync/runtime';
import { isAwardOp } from '@/lib/sync/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

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
  const attention = useAttentionOperations();
  const failures = attention
    .filter(isAwardOp)
    .filter((op) => op.classId === classId && (op.status === 'REJECTED' || op.status === 'EXPIRED'))
    .map((op) => ({ id: op.opId, kind: op.status === 'EXPIRED' ? ('expired' as const) : ('rejected' as const), item: awardView(op), detail: op.problem?.detail || undefined }));
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
              onPress={() => void syncEngine.dismiss(failure.id)}
            />
          </View>
        );
      })}
    </View>
  );
}
