import { useEffect } from 'react';
import { AccessibilityInfo, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface UndoSnackbarProps {
  message: string;
  /** Second line: "Sending…", "Waiting to send" or the seconds left to undo. */
  status: string;
  /** Null hides the button (the window passed). */
  onUndo: (() => void) | null;
  undoBusy: boolean;
  onDismiss: () => void;
}

/** The bar at the bottom after an award: what happened, and one tap to take it back while that is allowed. */
export function UndoSnackbar({ message, status, onUndo, undoBusy, onDismiss }: UndoSnackbarProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const insets = useSafeAreaInsets();

  // Screen readers get the award announced once (the live region alone is not reliable on iOS).
  useEffect(() => {
    void AccessibilityInfo.announceForAccessibility(message);
  }, [message]);

  return (
    <View
      accessibilityLiveRegion="polite"
      style={{
        position: 'absolute',
        left: spacing[4],
        right: spacing[4],
        bottom: Math.max(insets.bottom, spacing[3]) + spacing[2],
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[3],
        padding: spacing[3],
        paddingLeft: spacing[4],
        borderRadius: radii.xl,
        backgroundColor: colors.foreground,
      }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={t('mobile.points.snackbar.dismiss')}
        onPress={onDismiss}
        style={{ flex: 1, gap: 2, minHeight: minTouchTarget, justifyContent: 'center' }}>
        <Text variant="label" numberOfLines={2} style={{ color: colors.background }}>
          {message}
        </Text>
        <Text variant="caption" numberOfLines={1} style={{ color: colors.background, opacity: 0.8 }}>
          {status}
        </Text>
      </Pressable>
      {onUndo ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('mobile.points.snackbar.undo')}
          accessibilityState={{ disabled: undoBusy, busy: undoBusy }}
          disabled={undoBusy}
          onPress={onUndo}
          style={{
            minHeight: minTouchTarget,
            minWidth: minTouchTarget + 16,
            paddingHorizontal: spacing[4],
            borderRadius: radii.full,
            alignItems: 'center',
            justifyContent: 'center',
            backgroundColor: colors.secondary,
            opacity: undoBusy ? 0.6 : 1,
          }}>
          <Text variant="label" style={{ color: colors.secondaryForeground }}>
            {t('mobile.points.snackbar.undo')}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}
