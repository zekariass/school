import { View, type ViewProps } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

/** Rounded surface with a subtle shadow (border in dark mode, where shadows disappear). */
export function Card({ style, ...props }: ViewProps) {
  const { colors, radii, spacing, cardShadow, scheme } = useAppTheme();
  return (
    <View
      {...props}
      style={[
        {
          backgroundColor: colors.card,
          borderRadius: radii['2xl'],
          padding: spacing[5],
          gap: spacing[3],
          borderWidth: scheme === 'dark' ? 1 : 0,
          borderColor: colors.border,
          boxShadow: scheme === 'dark' ? undefined : cardShadow,
        },
        style,
      ]}
    />
  );
}
