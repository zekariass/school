import type { ReactNode } from 'react';
import { Modal, Pressable, ScrollView, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { useReduceMotion } from '@/lib/use-reduce-motion';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './text';

export interface SheetProps {
  visible: boolean;
  onClose: () => void;
  /** Heading of the sheet (also its accessible name). */
  title?: string;
  children: ReactNode;
}

/**
 * A bottom sheet: dims the screen, slides up (or fades with "reduce motion"), closes with the backdrop, the
 * system back gesture or the close button. Content scrolls when it is taller than 85% of the screen.
 */
export function Sheet({ visible, onClose, title, children }: SheetProps) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();
  const reduceMotion = useReduceMotion();

  return (
    <Modal
      visible={visible}
      transparent
      animationType={reduceMotion ? 'fade' : 'slide'}
      onRequestClose={onClose}
      supportedOrientations={['portrait', 'landscape']}>
      <View style={{ flex: 1, justifyContent: 'flex-end' }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('common.cancel')}
          onPress={onClose}
          style={{ ...absoluteFill, backgroundColor: colors.foreground, opacity: 0.4 }}
        />
        <View
          accessibilityViewIsModal
          style={{
            maxHeight: height * 0.85,
            backgroundColor: colors.background,
            borderTopLeftRadius: radii['2xl'],
            borderTopRightRadius: radii['2xl'],
            paddingTop: spacing[3],
            paddingBottom: Math.max(insets.bottom, spacing[4]),
          }}>
          <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{ alignSelf: 'center', width: 44, height: 5, borderRadius: 3, backgroundColor: colors.border, marginBottom: spacing[3] }}
          />
          <ScrollView
            keyboardShouldPersistTaps="handled"
            contentContainerStyle={{ paddingHorizontal: spacing[5], paddingBottom: spacing[3], gap: spacing[4] }}>
            {title ? <Text variant="heading">{title}</Text> : null}
            {children}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

const absoluteFill = { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 } as const;
