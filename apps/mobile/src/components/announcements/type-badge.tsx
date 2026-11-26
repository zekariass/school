import { SymbolView } from 'expo-symbols';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { useAppTheme } from '@/lib/theme/theme-provider';
import { TYPE_LABEL } from '@/lib/announcements/labels';
import type { AnnouncementType } from '@/lib/announcements/types';

import { Text } from '../ui';

const ICONS: Record<AnnouncementType, { ios: string; android: string } | null> = {
  NORMAL: null,
  IMPORTANT: { ios: 'exclamationmark.circle.fill', android: 'priority_high' },
  URGENT: { ios: 'exclamationmark.triangle.fill', android: 'warning' },
  SAFETY: { ios: 'shield.lefthalf.filled', android: 'shield' },
};

/**
 * The type of an announcement as an icon AND a word, never colour alone: "Important", "Urgent" or "Safety notice". A normal
 * announcement has no badge. Urgent and safety also get a stronger border colour, which is only a second cue.
 */
export function TypeBadge({ type }: { type: AnnouncementType }) {
  const t = useTranslations();
  const { colors, radii } = useAppTheme();
  const icon = ICONS[type];
  if (!icon) return null;
  const strong = type === 'URGENT' || type === 'SAFETY';
  const tint = strong ? colors.destructive : colors.warning;
  return (
    <View
      accessible
      accessibilityLabel={t(TYPE_LABEL[type])}
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        alignSelf: 'flex-start',
        gap: 6,
        paddingHorizontal: 10,
        paddingVertical: 4,
        borderRadius: radii.full,
        borderWidth: 1,
        borderColor: tint,
        backgroundColor: colors.card,
      }}>
      <SymbolView name={{ ios: icon.ios, android: icon.android, web: icon.android } as never} size={16} tintColor={tint} />
      <Text variant="label" style={{ color: tint }}>
        {t(TYPE_LABEL[type])}
      </Text>
    </View>
  );
}

/** The border colour of a card of this type (a second cue next to the badge). */
export function useTypeBorder(type: AnnouncementType, unread: boolean): string {
  const { colors } = useAppTheme();
  if (type === 'URGENT' || type === 'SAFETY') return colors.destructive;
  if (type === 'IMPORTANT') return colors.warning;
  return unread ? colors.primary : colors.border;
}
