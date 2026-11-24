import { ActivityIndicator, Pressable, StyleSheet, type PressableProps } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './text';

export interface ButtonProps extends Omit<PressableProps, 'children' | 'style'> {
  label: string;
  variant?: 'primary' | 'secondary' | 'ghost';
  loading?: boolean;
  /** Stretch to the container width (default) or hug the label. */
  fullWidth?: boolean;
}

export function Button({
  label,
  variant = 'primary',
  loading = false,
  fullWidth = true,
  disabled,
  accessibilityLabel,
  ...props
}: ButtonProps) {
  const { colors, radii, minTouchTarget } = useAppTheme();
  const inactive = disabled || loading;

  const palette = {
    primary: { bg: colors.primary, pressed: colors.ring, fg: colors.primaryForeground, border: 'transparent' },
    secondary: { bg: colors.secondary, pressed: colors.warning, fg: colors.secondaryForeground, border: 'transparent' },
    ghost: { bg: 'transparent', pressed: colors.muted, fg: colors.primary, border: colors.border },
  }[variant];

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityState={{ disabled: !!inactive, busy: loading }}
      disabled={inactive}
      hitSlop={4}
      {...props}
      style={({ pressed }) => [
        styles.base,
        {
          minHeight: minTouchTarget + 8,
          borderRadius: radii.full,
          backgroundColor: pressed ? palette.pressed : palette.bg,
          borderColor: palette.border,
          opacity: inactive ? 0.55 : 1,
          alignSelf: fullWidth ? 'stretch' : 'flex-start',
          transform: [{ scale: pressed ? 0.98 : 1 }],
        },
      ]}>
      {loading ? <ActivityIndicator color={palette.fg} /> : null}
      <Text variant="label" style={{ color: palette.fg, fontSize: 16, lineHeight: 24 }}>
        {label}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 24,
    paddingVertical: 10,
    borderWidth: 1,
  },
});
