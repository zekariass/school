import { SymbolView, type SymbolViewProps } from 'expo-symbols';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Card, Text } from '@/components/ui';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { iconFor } from '@/lib/points/categories';
import type { StudentBadge } from '@/lib/points/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

/** Badges a student earned (class and school points only; home points never earn them). */
export function BadgesSection({ badges }: { badges: readonly StudentBadge[] }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  const format = useFormatInstant();

  return (
    <Card>
      <Text variant="heading">{t('mobile.points.badges.title')}</Text>
      {badges.length === 0 ? (
        <Text tone="muted">{t('mobile.points.badges.empty')}</Text>
      ) : (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[3] }}>
          {badges.map((badge) => {
            const icon = iconFor(badge.icon);
            return (
              <View
                key={badge.badgeId}
                accessible
                accessibilityLabel={[badge.name, badge.description, t('mobile.points.badges.earned', { date: format(badge.awardedAt) })]
                  .filter(Boolean)
                  .join(', ')}
                style={{ width: '47%', alignItems: 'center', gap: spacing[1], padding: spacing[3], borderRadius: 20, backgroundColor: colors.accent }}>
                <SymbolView
                  name={{ ios: icon.ios, android: icon.android, web: icon.android } as SymbolViewProps['name']}
                  size={36}
                  tintColor={colors.accentForeground}
                />
                <Text variant="label" style={{ textAlign: 'center', color: colors.accentForeground }} numberOfLines={2}>
                  {badge.name}
                </Text>
                <Text variant="caption" style={{ textAlign: 'center', color: colors.accentForeground }} numberOfLines={1}>
                  {format(badge.awardedAt)}
                </Text>
              </View>
            );
          })}
        </View>
      )}
    </Card>
  );
}
