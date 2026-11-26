import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { addDays } from '@/lib/attendance/dates';
import { useFormatCivilDate } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface DateStepperProps {
  label: string;
  value: string;
  min: string;
  max: string;
  onChange: (date: string) => void;
}

/** A day picker without typing: the day in the user's calendar, with arrows for the day before and after. */
export function DateStepper({ label, value, min, max, onChange }: DateStepperProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const format = useFormatCivilDate();
  const earlier = addDays(value, -1);
  const later = addDays(value, 1);

  const arrow = (symbol: string, material: string, accessibilityLabel: string, target: string) => {
    const enabled = target >= min && target <= max;
    return (
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={accessibilityLabel}
        accessibilityState={{ disabled: !enabled }}
        disabled={!enabled}
        onPress={() => onChange(target)}
        style={{ width: minTouchTarget + 8, height: minTouchTarget + 8, alignItems: 'center', justifyContent: 'center', opacity: enabled ? 1 : 0.3 }}>
        <SymbolView name={{ ios: symbol, android: material, web: material } as SymbolViewProps['name']} size={24} tintColor={colors.primary} />
      </Pressable>
    );
  };

  return (
    <View style={{ gap: spacing[1] }}>
      <Text variant="label">{label}</Text>
      <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: colors.card, borderRadius: radii.lg, borderWidth: 1, borderColor: colors.input }}>
        {arrow('chevron.left', 'chevron_left', t('mobile.absence.form.dayBefore', { label }), earlier)}
        <View style={{ flex: 1, alignItems: 'center' }} accessible accessibilityLabel={`${label}: ${format(value)}`}>
          <Text variant="label" style={{ textAlign: 'center' }}>
            {format(value)}
          </Text>
        </View>
        {arrow('chevron.right', 'chevron_right', t('mobile.absence.form.dayAfter', { label }), later)}
      </View>
    </View>
  );
}
