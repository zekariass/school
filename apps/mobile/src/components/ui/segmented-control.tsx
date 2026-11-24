import { Pressable, View } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './text';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
}

export interface SegmentedControlProps<T extends string> {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  /** Accessible name of the group (the setting's label). */
  accessibilityLabel: string;
}

export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  accessibilityLabel,
}: SegmentedControlProps<T>) {
  const { colors, radii, minTouchTarget } = useAppTheme();
  return (
    <View
      accessibilityRole="radiogroup"
      accessibilityLabel={accessibilityLabel}
      style={{ flexDirection: 'row', backgroundColor: colors.muted, borderRadius: radii.full, padding: 4, gap: 4 }}>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            accessibilityLabel={option.label}
            onPress={() => onChange(option.value)}
            style={{
              flex: 1,
              minHeight: minTouchTarget,
              borderRadius: radii.full,
              alignItems: 'center',
              justifyContent: 'center',
              paddingHorizontal: 8,
              backgroundColor: selected ? colors.primary : 'transparent',
            }}>
            <Text
              variant="label"
              numberOfLines={1}
              style={{ color: selected ? colors.primaryForeground : colors.foreground }}>
              {option.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}
