import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/** A tappable checkbox (or radio) row: a symbol and a label, state in the accessibility tree and in the symbol (not colour only). */
export function CheckRow({
  label,
  hint,
  checked,
  onToggle,
  radio = false,
  disabled = false,
}: {
  label: string;
  hint?: string | undefined;
  checked: boolean;
  onToggle: () => void;
  radio?: boolean;
  disabled?: boolean;
}) {
  const { colors, minTouchTarget, radii, spacing } = useAppTheme();
  const on = radio
    ? { ios: 'largecircle.fill.circle', android: 'radio_button_checked' }
    : { ios: 'checkmark.square.fill', android: 'check_box' };
  const off = radio ? { ios: 'circle', android: 'radio_button_unchecked' } : { ios: 'square', android: 'check_box_outline_blank' };
  const icon = checked ? on : off;
  return (
    <Pressable
      accessibilityRole={radio ? 'radio' : 'checkbox'}
      accessibilityState={{ checked, disabled }}
      accessibilityLabel={hint ? `${label}. ${hint}` : label}
      disabled={disabled}
      onPress={onToggle}
      style={({ pressed }) => ({
        minHeight: minTouchTarget,
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[3],
        paddingHorizontal: spacing[2],
        paddingVertical: spacing[2],
        borderRadius: radii.lg,
        backgroundColor: pressed ? colors.muted : 'transparent',
        opacity: disabled ? 0.5 : 1,
      })}>
      <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as never} size={24} tintColor={checked ? colors.primary : colors.mutedForeground} />
      <View style={{ flex: 1 }}>
        <Text>{label}</Text>
        {hint ? <Text variant="caption">{hint}</Text> : null}
      </View>
    </Pressable>
  );
}
