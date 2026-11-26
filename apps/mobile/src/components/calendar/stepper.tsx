import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { Pressable, View } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

export interface StepperLabels {
  smallBack: string;
  smallForward: string;
  largeBack?: string;
  largeForward?: string;
}

/**
 * A value between step buttons: one small step (a day, a quarter hour) each way and, when asked, a large one (a week, an hour).
 * Steppers instead of a native picker, because a native picker always speaks the phone's time zone and the event's own zone is
 * what is being chosen.
 */
export function Stepper({
  label,
  value,
  labels,
  onStep,
  disabled = false,
  error,
}: {
  label: string;
  value: string;
  labels: StepperLabels;
  /** `size` 1 is the small step, 2 the large one; `direction` -1 back, 1 forward. */
  onStep: (direction: -1 | 1, size: 1 | 2) => void;
  disabled?: boolean;
  error?: string | undefined;
}) {
  const { colors, spacing } = useAppTheme();
  return (
    <View style={{ gap: spacing[1] }}>
      <Text variant="label">{label}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[1] }}>
        {labels.largeBack ? <Step label={labels.largeBack} ios="chevron.left.2" android="keyboard_double_arrow_left" disabled={disabled} onPress={() => onStep(-1, 2)} /> : null}
        <Step label={labels.smallBack} ios="chevron.left" android="chevron_left" disabled={disabled} onPress={() => onStep(-1, 1)} />
        <Text style={{ flex: 1, textAlign: 'center' }} accessibilityLiveRegion="polite" accessibilityLabel={`${label}: ${value}`}>
          {value}
        </Text>
        <Step label={labels.smallForward} ios="chevron.right" android="chevron_right" disabled={disabled} onPress={() => onStep(1, 1)} />
        {labels.largeForward ? <Step label={labels.largeForward} ios="chevron.right.2" android="keyboard_double_arrow_right" disabled={disabled} onPress={() => onStep(1, 2)} /> : null}
      </View>
      {error ? (
        <Text variant="caption" style={{ color: colors.destructive }} accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : null}
    </View>
  );
}

function Step({ label, ios, android, disabled, onPress }: { label: string; ios: string; android: string; disabled: boolean; onPress: () => void }) {
  const { colors, minTouchTarget, radii } = useAppTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={{ width: minTouchTarget, height: minTouchTarget, borderRadius: radii.full, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.muted, opacity: disabled ? 0.4 : 1 }}>
      <SymbolView name={{ ios, android, web: android } as SymbolViewProps['name']} size={18} tintColor={colors.primary} />
    </Pressable>
  );
}
