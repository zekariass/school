import { useNetworkState } from 'expo-network';
import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { useState } from 'react';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Text } from '@/components/ui';
import { useAgeText } from '@/lib/sync/age-text';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface OfflineNoteProps {
  /** The data on the screen is the phone's saved copy. */
  fromCache: boolean;
  /** When the server last confirmed it. */
  fetchedAt: number | undefined;
}

/** "Offline · saved copy, updated 3 h ago": shown only when the phone is offline or the data is its own copy. */
export function OfflineNote({ fromCache, fetchedAt }: OfflineNoteProps) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const network = useNetworkState();
  const ageText = useAgeText();
  const [openedAt] = useState(() => Date.now());
  const offline = network.isConnected === false;
  if (!offline && !fromCache) return null;
  const age = fetchedAt !== undefined ? ageText(fetchedAt, openedAt) : null;
  return (
    <View accessible accessibilityRole="text" style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
      <SymbolView
        name={{ ios: 'wifi.slash', android: 'wifi_off', web: 'wifi_off' } as SymbolViewProps['name']}
        size={16}
        tintColor={colors.warning}
      />
      <Text variant="caption" style={{ flex: 1 }}>
        {[offline ? t('mobile.sync.offline') : null, fromCache && age ? t('mobile.sync.savedCopy', { age }) : null].filter(Boolean).join(' · ')}
      </Text>
    </View>
  );
}
