import { SymbolView } from 'expo-symbols';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { TYPE_LABEL } from '@/lib/announcements/labels';
import { isUnreadCard, needsAcknowledgementCard } from '@/lib/announcements/inbox';
import type { AnnouncementCard } from '@/lib/announcements/types';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { TypeBadge, useTypeBorder } from './type-badge';

/**
 * One announcement in a list: title, who and when, a short excerpt, and the state as words with an icon (new, please confirm,
 * confirmed, withdrawn, draft, scheduled). `showSchool` names the school when the person belongs to several.
 */
export function AnnouncementRow({ card, showSchool, authorView, onPress }: { card: AnnouncementCard; showSchool: boolean; authorView?: boolean; onPress: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const format = useFormatInstant();
  const unread = !authorView && isUnreadCard(card);
  const needsAck = !authorView && needsAcknowledgementCard(card);
  const border = useTypeBorder(card.type, unread);
  const withdrawn = card.status === 'WITHDRAWN';
  const when = card.status === 'SCHEDULED' && card.scheduledFor ? card.scheduledFor : (card.publishedAt ?? card.createdAt);

  const states: { key: string; icon: { ios: string; android: string }; text: string; tone: 'primary' | 'warning' | 'muted' | 'success' }[] = [];
  if (card.status === 'DRAFT') states.push({ key: 'draft', icon: { ios: 'pencil', android: 'edit' }, text: t('mobile.announcements.status.draft'), tone: 'muted' });
  if (card.status === 'SCHEDULED') states.push({ key: 'scheduled', icon: { ios: 'clock', android: 'schedule' }, text: t('mobile.announcements.status.scheduled'), tone: 'warning' });
  if (withdrawn) states.push({ key: 'withdrawn', icon: { ios: 'arrow.uturn.backward.circle', android: 'undo' }, text: t('mobile.announcements.status.withdrawn'), tone: 'muted' });
  if (unread) states.push({ key: 'new', icon: { ios: 'circle.fill', android: 'fiber_manual_record' }, text: t('mobile.announcements.status.new'), tone: 'primary' });
  if (needsAck) states.push({ key: 'ack', icon: { ios: 'hand.raised.fill', android: 'front_hand' }, text: t('mobile.announcements.status.needsAck'), tone: 'warning' });
  if (!authorView && card.requiresAcknowledgement && card.acknowledgedAt && card.status === 'PUBLISHED') {
    states.push({ key: 'done', icon: { ios: 'checkmark.circle.fill', android: 'check_circle' }, text: t('mobile.announcements.status.confirmed'), tone: 'success' });
  }

  const label = [
    unread ? t('mobile.announcements.status.new') : null,
    t(TYPE_LABEL[card.type]),
    card.title,
    showSchool ? card.schoolName : null,
    card.authorName ? t('mobile.announcements.from', { name: card.authorName }) : null,
    format(when),
    ...states.filter((state) => state.key !== 'new').map((state) => state.text),
  ]
    .filter(Boolean)
    .join('. ');

  const tone = (value: 'primary' | 'warning' | 'muted' | 'success') => (value === 'primary' ? colors.primary : value === 'warning' ? colors.warning : value === 'success' ? colors.success : colors.mutedForeground);

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={({ pressed }) => ({
        gap: spacing[2],
        padding: spacing[4],
        borderRadius: radii.xl,
        backgroundColor: pressed ? colors.muted : colors.card,
        borderWidth: unread || card.type === 'URGENT' || card.type === 'SAFETY' ? 2 : 1,
        borderColor: border,
        opacity: withdrawn ? 0.75 : 1,
      })}>
      <TypeBadge type={card.type} />
      <Text variant={unread ? 'label' : 'body'} numberOfLines={2} style={withdrawn ? { textDecorationLine: 'line-through' } : undefined}>
        {card.title}
      </Text>
      {card.excerpt && !withdrawn ? (
        <Text tone="muted" numberOfLines={2}>
          {card.excerpt}
        </Text>
      ) : null}
      <Text variant="caption" numberOfLines={2}>
        {[showSchool ? card.schoolName : null, card.authorName ? t('mobile.announcements.from', { name: card.authorName }) : null, format(when)].filter(Boolean).join(' · ')}
      </Text>
      {states.length > 0 ? (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[3] }}>
          {states.map((state) => (
            <View key={state.key} style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
              <SymbolView name={{ ios: state.icon.ios, android: state.icon.android, web: state.icon.android } as never} size={14} tintColor={tone(state.tone)} />
              <Text variant="caption" style={{ color: tone(state.tone) }}>
                {state.text}
              </Text>
            </View>
          ))}
        </View>
      ) : null}
    </Pressable>
  );
}
