import { useRef, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { ProblemBanner } from '@/components/problem-banner';
import { Sheet, Text } from '@/components/ui';
import { newOpId } from '@/lib/points/op-id';
import { POINT_CHOICES, formatSigned, signedPoints, sortedCategories } from '@/lib/points/categories';
import { playFeedback } from '@/lib/points/feedback';
import { useAwardHomePoints, useHomeCategories, type PointCategory } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { useReduceMotion } from '@/lib/use-reduce-motion';

import { CategoryTile } from './category-tile';

/**
 * A guardian gives home points: HOME skills only, online only. The plain note at the top says what home points
 * are. The op id is made once per (skill, points) attempt, so a retry after a dropped answer cannot award twice.
 */
export function HomeAwardSheet({
  visible,
  onClose,
  schoolId,
  studentId,
  childName,
  onGiven,
}: {
  visible: boolean;
  onClose: () => void;
  schoolId: string;
  studentId: string;
  childName: string;
  onGiven: (message: string) => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const reduceMotion = useReduceMotion();
  const categories = useHomeCategories(schoolId, studentId, visible);
  const award = useAwardHomePoints();
  const [override, setOverride] = useState<number | null>(null);
  const attempt = useRef<{ key: string; opId: string } | null>(null);

  const skills = sortedCategories(categories.data ?? []).positive;

  async function give(category: PointCategory) {
    if (award.isPending) return;
    const key = `${category.id}:${override ?? 'default'}`;
    // A retry of the very same choice (after a failure) keeps its op id; anything else is a new action.
    if (attempt.current?.key !== key) attempt.current = { key, opId: newOpId() };
    try {
      await award.mutateAsync({
        schoolId,
        studentId,
        categoryId: category.id,
        points: override,
        note: null,
        clientOpId: attempt.current.opId,
      });
      attempt.current = null;
      playFeedback('award', reduceMotion);
      const points = signedPoints({ polarity: category.polarity, defaultPoints: category.defaultPoints }, override);
      onGiven(t('mobile.points.home.given', { skill: category.name, points: formatSigned(points), name: childName }));
      setOverride(null);
      award.reset();
      onClose();
    } catch {
      // The banner below shows the API's own message (60 a day, no permission ...).
    }
  }

  return (
    <Sheet
      visible={visible}
      onClose={() => {
        award.reset();
        onClose();
      }}
      title={t('mobile.points.home.title', { name: childName })}>
      <View style={{ padding: spacing[3], borderRadius: radii.lg, backgroundColor: colors.accent }}>
        <Text style={{ color: colors.accentForeground }}>{t('mobile.points.home.note')}</Text>
      </View>

      <View style={{ gap: spacing[2] }}>
        <Text variant="label">{t('mobile.points.sheet.pointsLabel')}</Text>
        <View accessibilityRole="radiogroup" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
          {POINT_CHOICES.map((choice) => {
            const selected = choice === override;
            return (
              <Pressable
                key={choice ?? 'default'}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                accessibilityLabel={choice === null ? t('mobile.points.sheet.pointsDefault') : t('mobile.points.sheet.pointsChoice', { count: choice })}
                onPress={() => setOverride(choice)}
                style={{
                  minWidth: minTouchTarget + 8,
                  minHeight: minTouchTarget,
                  paddingHorizontal: spacing[4],
                  borderRadius: radii.full,
                  alignItems: 'center',
                  justifyContent: 'center',
                  backgroundColor: selected ? colors.primary : colors.muted,
                }}>
                <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
                  {choice === null ? t('mobile.points.sheet.pointsDefault') : String(choice)}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {award.isError ? (
        <ProblemBanner error={award.error} fallbackTitle={t('mobile.points.home.failedTitle')} fallbackBody={t('mobile.points.home.failedBody')} />
      ) : null}

      {categories.isPending ? (
        <Text tone="muted">{t('common.loading')}</Text>
      ) : categories.isError ? (
        <ProblemBanner
          error={categories.error}
          fallbackTitle={t('mobile.points.home.failedTitle')}
          fallbackBody={t('mobile.points.home.failedBody')}
          onRetry={() => void categories.refetch()}
        />
      ) : skills.length === 0 ? (
        <Text tone="muted">{t('mobile.points.home.noSkills')}</Text>
      ) : (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2], justifyContent: 'space-between' }}>
          {skills.map((category) => (
            <CategoryTile
              key={category.id}
              category={category}
              pointsOverride={override}
              disabled={award.isPending}
              accessibilityVerb={t('mobile.points.home.giveVerb')}
              onPress={() => void give(category)}
            />
          ))}
        </View>
      )}
    </Sheet>
  );
}
