import { router, type Href } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { badgeCount } from '@/lib/announcements/inbox';
import { useAnnouncementSummary } from '@/lib/announcements/queries';
import { badgeLabel } from '@/lib/notifications/cache';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

/**
 * The entry to the announcements inbox, next to the notification bell on every tab: a megaphone with the count of unread
 * announcements or of those that wait for a confirmation (the larger of the two; the screen shows both). Counts only.
 */
export function AnnouncementsButton() {
  const t = useTranslations();
  const { colors, minTouchTarget } = useAppTheme();
  const { data } = useAnnouncementSummary();
  const count = badgeCount(data);
  const label = badgeLabel(count);
  const accessibilityLabel = data && (data.unread > 0 || data.needingAcknowledgement > 0)
    ? t('mobile.announcements.entryAttention', { unread: data.unread, needsAck: data.needingAcknowledgement })
    : t('mobile.announcements.entry');

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      onPress={() => router.push('/announcements' as Href)}
      hitSlop={4}
      style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
      <SymbolView name={{ ios: 'megaphone.fill', android: 'campaign', web: 'campaign' }} size={26} tintColor={colors.primary} />
      {label ? (
        <View
          style={{
            position: 'absolute',
            top: 2,
            end: 0,
            minWidth: 20,
            height: 20,
            paddingHorizontal: 5,
            borderRadius: 10,
            backgroundColor: colors.destructive,
            alignItems: 'center',
            justifyContent: 'center',
          }}>
          <Text variant="caption" style={{ color: colors.destructiveForeground, fontSize: 12, lineHeight: 16 }}>
            {label}
          </Text>
        </View>
      ) : null}
    </Pressable>
  );
}
