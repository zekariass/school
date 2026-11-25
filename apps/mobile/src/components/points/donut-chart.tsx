import { View } from 'react-native';
import Svg, { Circle, G } from 'react-native-svg';
import { useTranslations } from 'use-intl';

import { palette } from '@school/design-tokens';

import { Text } from '@/components/ui';
import { categoryColor, isLightColor } from '@/lib/points/categories';
import { arcDash, donutModel, percentages, type CategoryTotalLike } from '@/lib/points/donut';
import { useAppTheme } from '@/lib/theme/theme-provider';

// Used when a category has no colour of its own; token values, never typed colours.
const FALLBACKS = [palette.meadow[500], palette.sky[500], palette.grape[500], palette.sunshine[500], palette.coral[500]];

const SIZE = 168;
const STROKE = 26;

/**
 * Points by skill as a donut with a legend. The picture is hidden from screen readers; the legend rows (and the
 * summary label of the whole block) say the same in words.
 */
export function DonutChart({ categories, total }: { categories: readonly CategoryTotalLike[]; total: number }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const model = donutModel(categories);
  const shares = percentages(model.slices);
  const radius = (SIZE - STROKE) / 2;
  const colorOf = (index: number, own: string | null | undefined) => (own && categoryColor({ color: own }) ? own : FALLBACKS[index % FALLBACKS.length]!);

  if (model.slices.length === 0) {
    return <Text tone="muted">{t('mobile.points.donut.empty')}</Text>;
  }

  const summary = model.slices
    .map((slice, index) => t('mobile.points.donut.item', { name: slice.name, points: slice.points, percent: shares[index] ?? 0 }))
    .join('; ');

  return (
    <View accessible accessibilityLabel={t('mobile.points.donut.summary', { total, items: summary })} style={{ gap: spacing[4], alignItems: 'center' }}>
      <View style={{ width: SIZE, height: SIZE }} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        <Svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`}>
          <Circle cx={SIZE / 2} cy={SIZE / 2} r={radius} stroke={colors.muted} strokeWidth={STROKE} fill="none" />
          <G rotation={-90} origin={`${SIZE / 2}, ${SIZE / 2}`}>
            {model.slices.map((slice, index) => {
              const dash = arcDash(slice, radius, model.slices.length > 1 ? 3 : 0);
              return (
                <Circle
                  key={slice.categoryId}
                  cx={SIZE / 2}
                  cy={SIZE / 2}
                  r={radius}
                  stroke={colorOf(index, slice.color)}
                  strokeWidth={STROKE}
                  fill="none"
                  strokeDasharray={dash.dashArray}
                  strokeDashoffset={dash.dashOffset}
                />
              );
            })}
          </G>
        </Svg>
      </View>
      <View style={{ alignSelf: 'stretch', gap: spacing[2] }} importantForAccessibility="no-hide-descendants" accessibilityElementsHidden>
        {model.slices.map((slice, index) => {
          const color = colorOf(index, slice.color);
          return (
            <View key={slice.categoryId} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
              <View style={{ width: 16, height: 16, borderRadius: 8, backgroundColor: color, borderWidth: isLightColor(color) ? 1 : 0, borderColor: colors.border }} />
              <Text style={{ flex: 1 }} numberOfLines={1}>
                {slice.name}
              </Text>
              <Text variant="label">{slice.points}</Text>
              <Text variant="caption" style={{ width: 44, textAlign: 'right' }}>
                {shares[index] ?? 0}%
              </Text>
            </View>
          );
        })}
      </View>
    </View>
  );
}
