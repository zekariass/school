import { router } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { isUnreadCard } from '@/lib/announcements/inbox';
import { useFeaturedAnnouncement } from '@/lib/announcements/queries';
import { TYPE_LABEL } from '@/lib/announcements/labels';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { Tint } from './tint';

/**
 * The newest announcement the API lists for the person, as a tinted card on top of the feed that opens the announcement. Its
 * title is shown as plain text (React escapes it; nothing is parsed) and never leaves memory. No announcement, no card.
 * `schoolId` narrows it to the school of a class feed; a parent's home feed asks across schools.
 */
export function FeedAnnouncementCard({ schoolId }: { schoolId: string | null }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const format = useFormatInstant();
  const featured = useFeaturedAnnouncement(schoolId);
  const card = featured.data;
  if (!card) return null;

  const unread = isUnreadCard(card);
  const when = format(card.publishedAt ?? card.createdAt);
  const type = card.type === 'NORMAL' ? t('mobile.feed.announcement.label') : t(TYPE_LABEL[card.type]);
  const details = [card.authorName ? t('mobile.announcements.from', { name: card.authorName }) : null, when].filter(Boolean).join(' · ');

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={[unread ? t('mobile.announcements.status.new') : null, type, card.title, details].filter(Boolean).join('. ')}
      accessibilityHint={t('mobile.feed.announcement.open')}
      onPress={() => router.push({ pathname: '/announcements/[announcementId]', params: { announcementId: card.id, schoolId: card.schoolId } })}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[3],
        padding: spacing[4],
        borderRadius: radii.xl,
        overflow: 'hidden',
        backgroundColor: colors.card,
        borderWidth: 1,
        borderColor: unread ? colors.primary : colors.border,
        opacity: pressed ? 0.85 : 1,
      })}>
      <Tint color={colors.primary} opacity={0.12} />
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{ width: minTouchTarget, height: minTouchTarget, borderRadius: radii.full, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center' }}>
        <SymbolView name={{ ios: 'megaphone.fill', android: 'campaign', web: 'campaign' }} size={22} tintColor={colors.primaryForeground} />
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
          <Text variant="label" numberOfLines={1} style={{ color: colors.foreground, letterSpacing: 0.5, flexShrink: 1 }}>
            {type.toUpperCase()}
          </Text>
          {unread ? (
            <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colors.destructive }} />
          ) : null}
        </View>
        <Text variant="heading" numberOfLines={2}>
          {card.title}
        </Text>
        <Text variant="caption" numberOfLines={1}>
          {details}
        </Text>
      </View>
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{ width: 32, height: 32, borderRadius: 16, backgroundColor: colors.card, alignItems: 'center', justifyContent: 'center' }}>
        <SymbolView name={{ ios: 'chevron.right', android: 'chevron_right', web: 'chevron_right' }} size={16} tintColor={colors.primary} />
      </View>
    </Pressable>
  );
}
