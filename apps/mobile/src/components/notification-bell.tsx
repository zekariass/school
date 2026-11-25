import { router, type Href } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { badgeLabel } from '@/lib/notifications/cache';
import { useUnreadCount } from '@/lib/notifications/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './ui';

/** Bell with the unread count; opens the notification inbox. */
export function NotificationBell() {
  const t = useTranslations();
  const { colors, minTouchTarget } = useAppTheme();
  const { data } = useUnreadCount();
  const label = badgeLabel(data);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label ? t('mobile.notifications.bellLabelUnread', { count: data ?? 0 }) : t('mobile.notifications.bellLabel')}
      onPress={() => router.push('/notifications' as Href)}
      hitSlop={4}
      style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
      <SymbolView name={{ ios: 'bell.fill', android: 'notifications', web: 'notifications' }} size={26} tintColor={colors.primary} />
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
