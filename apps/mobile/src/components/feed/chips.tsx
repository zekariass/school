import { Pressable, ScrollView } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

export interface Chip<T extends string> {
  value: T;
  label: string;
}

/** A row of single-choice chips that scrolls sideways (a filter: all children, one child; all posts, drafts...). */
export function Chips<T extends string>({
  chips,
  value,
  onChange,
  accessibilityLabel,
}: {
  chips: readonly Chip<T>[];
  value: T;
  onChange: (value: T) => void;
  accessibilityLabel: string;
}) {
  const { colors, radii, minTouchTarget, spacing } = useAppTheme();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      accessibilityRole="radiogroup"
      accessibilityLabel={accessibilityLabel}
      contentContainerStyle={{ gap: spacing[2], paddingVertical: 2 }}>
      {chips.map((chip) => {
        const selected = chip.value === value;
        return (
          <Pressable
            key={chip.value}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            accessibilityLabel={chip.label}
            onPress={() => onChange(chip.value)}
            style={{
              minHeight: minTouchTarget - 4,
              paddingHorizontal: 16,
              justifyContent: 'center',
              borderRadius: radii.full,
              borderWidth: 1,
              borderColor: selected ? colors.primary : colors.border,
              backgroundColor: selected ? colors.primary : colors.card,
            }}>
            <Text variant="label" style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
              {chip.label}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}
