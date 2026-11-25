import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { Pressable, View } from 'react-native';

import { palette } from '@school/design-tokens';

import { Text } from '@/components/ui';
import { categoryColor, formatSigned, iconFor, isLightColor, signedPoints } from '@/lib/points/categories';
import type { PointCategory } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface CategoryIconProps {
  category: Pick<PointCategory, 'icon' | 'color' | 'polarity'>;
  size?: number;
}

/** The skill's round icon: its own colour from the school's settings, the theme's points colour otherwise. */
export function CategoryIcon({ category, size = 48 }: CategoryIconProps) {
  const { colors } = useAppTheme();
  const own = categoryColor(category);
  const background = own ?? (category.polarity === 'NEEDS_WORK' ? colors.pointsNeedsWork : colors.pointsPositive);
  const symbol = own ? (isLightColor(own) ? palette.slate[900] : palette.white) : colors.primaryForeground;
  const icon = iconFor(category.icon);
  return (
    <View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: background, alignItems: 'center', justifyContent: 'center' }}>
      <SymbolView
        name={{ ios: icon.ios, android: icon.android, web: icon.android } as SymbolViewProps['name']}
        size={size * 0.5}
        tintColor={symbol}
      />
    </View>
  );
}

export interface CategoryTileProps {
  category: PointCategory;
  /** The teacher's points choice, or null for the skill's own default. */
  pointsOverride: number | null;
  onPress: () => void;
  disabled?: boolean;
  /** Spoken before the skill name, e.g. "Award". */
  accessibilityVerb: string;
}

/** A skill tile of the award sheet: big, colourful, one tap to award. */
export function CategoryTile({ category, pointsOverride, onPress, disabled, accessibilityVerb }: CategoryTileProps) {
  const { colors, radii, spacing } = useAppTheme();
  const points = signedPoints({ polarity: category.polarity, defaultPoints: category.defaultPoints }, pointsOverride);
  const needsWork = category.polarity === 'NEEDS_WORK';
  const label = `${accessibilityVerb} ${category.name} ${formatSigned(points)}`;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => ({
        width: '48%',
        minHeight: 112,
        borderRadius: radii.xl,
        padding: spacing[3],
        gap: spacing[2],
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: colors.card,
        borderWidth: 2,
        borderColor: pressed ? colors.primary : colors.border,
        opacity: disabled ? 0.5 : 1,
        transform: [{ scale: pressed ? 0.97 : 1 }],
      })}>
      <CategoryIcon category={category} size={52} />
      <Text variant="label" numberOfLines={2} style={{ textAlign: 'center' }}>
        {category.name}
      </Text>
      <Text variant="caption" style={{ color: needsWork ? colors.pointsNeedsWork : colors.success }}>
        {formatSigned(points)}
      </Text>
    </Pressable>
  );
}
