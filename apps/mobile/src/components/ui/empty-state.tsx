import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import type { ReactNode } from 'react';
import { View } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './text';

export interface EmptyStateProps {
  /** SF Symbol (iOS) and Material Symbol (Android) names. */
  icon: { ios: string; android: string };
  title: string;
  body: string;
  /** Optional action below the text (a Button). */
  children?: ReactNode;
}

export function EmptyState({ icon, title, body, children }: EmptyStateProps) {
  const { colors, spacing } = useAppTheme();
  return (
    <View
      accessible={false}
      style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing[3], paddingHorizontal: spacing[4] }}>
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{
          width: 104,
          height: 104,
          borderRadius: 52,
          backgroundColor: colors.accent,
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: spacing[2],
        }}>
        <SymbolView
          name={{ ios: icon.ios, android: icon.android, web: icon.android } as SymbolViewProps['name']}
          size={48}
          tintColor={colors.accentForeground}
        />
      </View>
      <Text variant="heading" style={{ textAlign: 'center' }}>
        {title}
      </Text>
      <Text tone="muted" style={{ textAlign: 'center', maxWidth: 320 }}>
        {body}
      </Text>
      {children ? <View style={{ alignSelf: 'stretch', marginTop: spacing[3], gap: spacing[3] }}>{children}</View> : null}
    </View>
  );
}
