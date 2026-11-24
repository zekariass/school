import { Text as RNText, type TextProps as RNTextProps } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';
import type { TextVariant } from '@/lib/theme/typography';

export type TextTone = 'default' | 'muted' | 'primary' | 'destructive' | 'onPrimary';

export interface TextProps extends RNTextProps {
  variant?: TextVariant;
  tone?: TextTone;
}

export function Text({ variant = 'body', tone, style, ...props }: TextProps) {
  const theme = useAppTheme();
  const { colors } = theme;
  const defaultTone: TextTone = variant === 'caption' ? 'muted' : 'default';
  const color = {
    default: colors.foreground,
    muted: colors.mutedForeground,
    primary: colors.primary,
    destructive: colors.destructive,
    onPrimary: colors.primaryForeground,
  }[tone ?? defaultTone];

  return (
    <RNText
      accessibilityRole={variant === 'title' || variant === 'heading' ? 'header' : undefined}
      {...props}
      style={[{ color, ...theme.text(variant) }, style]}
    />
  );
}
