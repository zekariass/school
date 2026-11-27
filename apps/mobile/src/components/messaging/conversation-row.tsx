import { router } from 'expo-router';
import { SymbolView } from 'expo-symbols';
import { memo } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { inboxTitle, isObserving, previewOf, unreadText } from '@/lib/messaging/derive';
import { GROUP_KIND_LABEL } from '@/lib/messaging/labels';
import type { ConversationSummary } from '@/lib/messaging/types';
import { useDisplayZone } from '@/lib/country/place';
import { messageTimeText } from '@/lib/messaging/time';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { useParticipantText } from './use-participant-text';

export function goToConversation(id: string) {
  router.push({ pathname: '/messages/[conversationId]', params: { conversationId: id } });
}

/**
 * One row of the inbox. The preview is the API's first 120 characters of the last message (never a body for a deleted or hidden
 * one). A conversation a guardian only observes says so and is strictly read-only inside.
 */
function Row({ item, now }: { item: ConversationSummary; now: number }) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const { locale } = usePreferences();
  const zone = useDisplayZone();
  const personText = useParticipantText();
  const observing = isObserving(item);
  const title = inboxTitle(item);
  const child = item.contextStudent?.displayName;

  const titleText = observing
    ? child
      ? t('mobile.messaging.inbox.observingTitle', { child })
      : t('mobile.messaging.inbox.observingTitleNoChild')
    : title.kind === 'person'
      ? personText(title.person, item.myActingAs)
      : title.kind === 'title'
        ? title.title
        : t(GROUP_KIND_LABEL[title.groupKind]);

  const preview = previewOf(item);
  const previewText =
    preview.kind === 'text'
      ? preview.text
      : preview.kind === 'tombstone'
        ? t('mobile.messaging.inbox.previewRemoved')
        : preview.kind === 'image'
          ? t('mobile.messaging.inbox.previewPhoto')
          : t('mobile.messaging.inbox.previewNone');
  const unread = unreadText(item.unreadCount);
  const when = messageTimeText(item.lastMessage?.sentAt ?? item.activityAt, now, locale, zone);
  const pending = item.status === 'PENDING_APPROVAL';

  const a11y = [
    titleText,
    observing ? t('mobile.messaging.inbox.observing') : null,
    unread ? t('mobile.messaging.inbox.unread', { count: unread }) : null,
    item.muted ? t('mobile.messaging.inbox.muted') : null,
    previewText,
    when,
  ]
    .filter(Boolean)
    .join('. ');

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={a11y}
      onPress={() => goToConversation(item.id)}
      style={({ pressed }) => ({
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing[3],
        minHeight: minTouchTarget + 16,
        padding: spacing[3],
        borderRadius: radii.xl,
        backgroundColor: pressed ? colors.muted : colors.card,
        borderWidth: 1,
        borderColor: colors.border,
      })}>
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={{ width: 44, height: 44, borderRadius: 22, backgroundColor: colors.accent, alignItems: 'center', justifyContent: 'center' }}>
        <SymbolView
          name={{
            ios: observing ? 'eye' : item.type === 'DIRECT' ? 'person.fill' : 'person.3.fill',
            android: observing ? 'visibility' : item.type === 'DIRECT' ? 'person' : 'groups',
            web: 'person',
          }}
          size={22}
          tintColor={colors.accentForeground}
        />
      </View>
      <View style={{ flex: 1, gap: 2 }}>
        <Text variant="label" numberOfLines={1} style={{ fontWeight: unread ? '700' : '500' }}>
          {titleText}
        </Text>
        {observing ? (
          <Text variant="caption" tone="primary" numberOfLines={1}>
            {t('mobile.messaging.inbox.observing')}
          </Text>
        ) : pending ? (
          <Text variant="caption" tone="primary" numberOfLines={1}>
            {t('mobile.messaging.conversation.pendingShort')}
          </Text>
        ) : null}
        <Text variant="caption" numberOfLines={1} style={preview.kind === 'tombstone' ? { fontStyle: 'italic' } : undefined}>
          {previewText}
        </Text>
      </View>
      <View style={{ alignItems: 'flex-end', gap: 6 }}>
        <Text variant="caption" numberOfLines={1}>
          {when}
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
          {item.muted ? <SymbolView name={{ ios: 'bell.slash.fill', android: 'notifications_off', web: 'notifications_off' }} size={14} tintColor={colors.mutedForeground} /> : null}
          {unread ? (
            <View style={{ minWidth: 22, height: 22, borderRadius: 11, paddingHorizontal: 6, backgroundColor: item.muted ? colors.mutedForeground : colors.primary, alignItems: 'center', justifyContent: 'center' }}>
              <Text variant="caption" tone="onPrimary" style={{ fontWeight: '700' }}>
                {unread}
              </Text>
            </View>
          ) : null}
        </View>
      </View>
    </Pressable>
  );
}

export const ConversationRow = memo(Row);
