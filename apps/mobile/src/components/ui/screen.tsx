import type { ReactNode } from 'react';
import { ScrollView, StyleSheet, View, type RefreshControlProps, type StyleProp, type ViewStyle } from 'react-native';
import { SafeAreaView, type Edge } from 'react-native-safe-area-context';

import { useAppTheme } from '@/lib/theme/theme-provider';

export interface ScreenProps {
  children: ReactNode;
  /** Scrollable content (forms, lists, pull-to-refresh). */
  scroll?: boolean;
  refreshControl?: React.ReactElement<RefreshControlProps>;
  /** Safe-area edges to pad. Tab screens skip the bottom (the tab bar owns it); header screens skip the top. */
  edges?: readonly Edge[];
  contentStyle?: StyleProp<ViewStyle>;
}

/** Background + safe area + the app's standard horizontal padding. */
export function Screen({ children, scroll = false, refreshControl, edges = ['top'], contentStyle }: ScreenProps) {
  const { colors, spacing } = useAppTheme();
  const padding = { paddingHorizontal: spacing[5], paddingVertical: spacing[4], gap: spacing[4] };

  return (
    <SafeAreaView edges={edges} style={[styles.fill, { backgroundColor: colors.background }]}>
      {scroll ? (
        <ScrollView
          style={styles.fill}
          contentContainerStyle={[styles.scrollContent, padding, contentStyle]}
          keyboardShouldPersistTaps="handled"
          contentInsetAdjustmentBehavior="automatic"
          refreshControl={refreshControl}>
          {children}
        </ScrollView>
      ) : (
        <View style={[styles.fill, padding, contentStyle]}>{children}</View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  scrollContent: { flexGrow: 1 },
});
