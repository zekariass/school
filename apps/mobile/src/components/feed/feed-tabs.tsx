import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { FEED_TABS, TAB_ICON, TAB_LABEL_KEY, type FeedTab } from '@/lib/feed/tabs';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * The strip under the header: Feed, Photos, Activities, Achievements. A rounded bar that floats over the curve of the band; the
 * chosen tab is a filled pill. A tab list for screen readers (each tab says whether it is selected). The tabs are server-side
 * filters: choosing one loads that list from the API, it never filters what is already on the screen.
 */
export function FeedTabs({ value, onChange }: { value: FeedTab; onChange: (tab: FeedTab) => void }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget, cardShadow, scheme } = useAppTheme();

  return (
    <View
      accessibilityRole="tablist"
      accessibilityLabel={t('mobile.feed.tabs.label')}
      style={{
        flexDirection: 'row',
        gap: spacing[1],
        marginHorizontal: spacing[5],
        marginTop: -spacing[6],
        padding: spacing[1],
        borderRadius: radii.xl,
        backgroundColor: colors.card,
        borderWidth: scheme === 'dark' ? 1 : 0,
        borderColor: colors.border,
        boxShadow: scheme === 'dark' ? undefined : cardShadow,
      }}>
      {FEED_TABS.map((tab) => {
        const selected = tab === value;
        const label = t(TAB_LABEL_KEY[tab]);
        const tint = selected ? colors.primaryForeground : colors.mutedForeground;
        return (
          <Pressable
            key={tab}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={label}
            onPress={() => onChange(tab)}
            style={{
              flex: 1,
              minHeight: minTouchTarget + spacing[3],
              alignItems: 'center',
              justifyContent: 'center',
              gap: 2,
              paddingHorizontal: 2,
              borderRadius: radii.lg,
              backgroundColor: selected ? colors.primary : 'transparent',
            }}>
            <SymbolView name={{ ios: TAB_ICON[tab].ios, android: TAB_ICON[tab].android, web: TAB_ICON[tab].android } as never} size={20} tintColor={tint} />
            <Text variant="caption" numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75} style={{ color: tint, fontSize: 12, lineHeight: 16 }}>
              {label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}
