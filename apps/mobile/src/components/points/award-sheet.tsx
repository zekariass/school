import { useState, type ReactNode } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Sheet, Text } from '@/components/ui';
import { POINT_CHOICES, sortedCategories } from '@/lib/points/categories';
import type { PointCategory } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { CategoryTile } from './category-tile';

export interface AwardSheetProps {
  visible: boolean;
  onClose: () => void;
  /** Who gets the award: an avatar and name, a count, or the whole class. */
  header: ReactNode;
  /** The sheet's accessible title (the target in words). */
  title: string;
  categories: readonly PointCategory[];
  loading?: boolean;
  /** Called once per tap on a skill: the sheet closes itself afterwards. */
  onAward: (category: PointCategory, pointsOverride: number | null) => void;
}

/**
 * The award sheet: skill tiles (positive first; needs-work only when the API returned some), and a quick choice
 * of how many points. One tap on a tile awards at once: no confirmation step, speed is the point.
 */
export function AwardSheet({ visible, onClose, header, title, categories, loading, onAward }: AwardSheetProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const [override, setOverride] = useState<number | null>(null);
  const { positive, needsWork } = sortedCategories(categories);

  function award(category: PointCategory) {
    onAward(category, override);
    setOverride(null);
  }

  return (
    <Sheet visible={visible} onClose={onClose} title={title}>
      {header}

      <View style={{ gap: spacing[2] }}>
        <Text variant="label">{t('mobile.points.sheet.pointsLabel')}</Text>
        <View accessibilityRole="radiogroup" style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
          {POINT_CHOICES.map((choice) => {
            const selected = choice === override;
            const label = choice === null ? t('mobile.points.sheet.pointsDefault') : String(choice);
            return (
              <Pressable
                key={choice ?? 'default'}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                accessibilityLabel={choice === null ? label : t('mobile.points.sheet.pointsChoice', { count: choice })}
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
                  {label}
                </Text>
              </Pressable>
            );
          })}
        </View>
      </View>

      {loading && categories.length === 0 ? (
        <Text tone="muted">{t('common.loading')}</Text>
      ) : categories.length === 0 ? (
        <Text tone="muted">{t('mobile.points.sheet.noSkills')}</Text>
      ) : (
        <>
          <View style={{ gap: spacing[2] }}>
            <Text variant="label">{t('mobile.points.sheet.positiveTitle')}</Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2], justifyContent: 'space-between' }}>
              {positive.map((category) => (
                <CategoryTile
                  key={category.id}
                  category={category}
                  pointsOverride={override}
                  accessibilityVerb={t('mobile.points.sheet.awardVerb')}
                  onPress={() => award(category)}
                />
              ))}
            </View>
          </View>
          {needsWork.length > 0 ? (
            <View style={{ gap: spacing[2] }}>
              <Text variant="label">{t('mobile.points.sheet.needsWorkTitle')}</Text>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2], justifyContent: 'space-between' }}>
                {needsWork.map((category) => (
                  <CategoryTile
                    key={category.id}
                    category={category}
                    pointsOverride={override}
                    accessibilityVerb={t('mobile.points.sheet.awardVerb')}
                    onPress={() => award(category)}
                  />
                ))}
              </View>
            </View>
          ) : null}
        </>
      )}
    </Sheet>
  );
}
