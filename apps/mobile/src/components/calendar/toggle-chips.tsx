import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { Pressable, ScrollView, View } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

export interface ToggleChip<T extends string> {
  value: T;
  label: string;
  icon?: { ios: string; android: string };
}

/**
 * A row of chips where each one switches on and off (the sources shown). A checked chip shows a tick as well as a fill, so the
 * state never rests on colour alone; at least one stays on (`minimum`).
 */
export function ToggleChips<T extends string>({
  chips,
  selected,
  onChange,
  accessibilityLabel,
  minimum = 1,
}: {
  chips: readonly ToggleChip<T>[];
  selected: readonly T[];
  onChange: (next: T[]) => void;
  accessibilityLabel: string;
  minimum?: number;
}) {
  const { colors, radii, minTouchTarget, spacing } = useAppTheme();
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} accessibilityLabel={accessibilityLabel} contentContainerStyle={{ gap: spacing[2], paddingVertical: 2 }}>
      {chips.map((chip) => {
        const on = selected.includes(chip.value);
        const foreground = on ? colors.primaryForeground : colors.foreground;
        return (
          <Pressable
            key={chip.value}
            accessibilityRole="checkbox"
            accessibilityState={{ checked: on }}
            accessibilityLabel={chip.label}
            onPress={() => {
              const next = on ? selected.filter((value) => value !== chip.value) : [...selected, chip.value];
              if (next.length >= minimum) onChange(next);
            }}
            style={{
              minHeight: minTouchTarget - 4,
              paddingHorizontal: 12,
              flexDirection: 'row',
              alignItems: 'center',
              gap: 6,
              borderRadius: radii.full,
              borderWidth: 1,
              borderColor: on ? colors.primary : colors.border,
              backgroundColor: on ? colors.primary : colors.card,
            }}>
            <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
              <SymbolView
                name={(on ? { ios: 'checkmark', android: 'check', web: 'check' } : { ios: chip.icon?.ios ?? 'circle', android: chip.icon?.android ?? 'circle', web: chip.icon?.android ?? 'circle' }) as SymbolViewProps['name']}
                size={16}
                tintColor={foreground}
              />
            </View>
            <Text variant="label" style={{ color: foreground }}>
              {chip.label}
            </Text>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}
