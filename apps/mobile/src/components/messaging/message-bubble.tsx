import { memo, useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { isSystemMessage, messageImages, messageText } from '@/lib/messaging/derive';
import type { OutboxEntry } from '@/lib/messaging/outbox';
import type { FeedMediaItem } from '@/lib/feed/types';
import { useDisplayZone } from '@/lib/country/place';
import { messageTimeText } from '@/lib/messaging/time';
import type { ActingAs, MessageItem } from '@/lib/messaging/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { LinkedText } from '../announcements/linked-text';
import { FeedImage } from '../feed/feed-image';
import { PhotoViewer } from '../feed/photo-viewer';
import { Text } from '../ui';

import { useParticipantText } from './use-participant-text';

/**
 * One message. Strictly plain text (`LinkedText`: only http and https become links, in the in-app browser). A deleted or hidden
 * message is a quiet line with no text, no images and no reactions, whatever else the payload carried. People are role labels.
 */
function Bubble({
  message,
  viewer,
  replyTo,
  seen,
  now,
  onOpenOptions,
  onToggleReaction,
  canReact,
}: {
  message: MessageItem;
  viewer: ActingAs;
  /** The text the reply points at (from the loaded messages), a label when it is not loaded, null when there is no reply. */
  replyTo: { author: string; snippet: string | null } | null;
  seen: boolean;
  now: number;
  /** Undefined: nothing to offer (a tombstone of somebody else, a system line). */
  onOpenOptions: (() => void) | undefined;
  onToggleReaction: (emoji: string, mine: boolean) => void;
  canReact: boolean;
}) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const { locale } = usePreferences();
  const zone = useDisplayZone();
  const personText = useParticipantText();
  const [viewing, setViewing] = useState<number | null>(null);

  if (isSystemMessage(message)) {
    return (
      <View style={{ alignItems: 'center', paddingVertical: spacing[2] }}>
        <Text variant="caption" style={{ textAlign: 'center' }}>
          {message.body ?? t('mobile.messaging.message.system')}
        </Text>
      </View>
    );
  }

  const mine = message.mine;
  const text = messageText(message);
  const images = messageImages(message);
  const tombstone = message.status === 'DELETED' || message.status === 'HIDDEN';
  const author = personText(message.sender, viewer);
  const time = messageTimeText(message.sentAt ?? message.scheduledFor, now, locale, zone);
  const media: FeedMediaItem[] = images.map((image, index) => ({ mediaId: image.mediaId, kind: 'IMAGE', sortOrder: index, variants: [...image.variants] as FeedMediaItem['variants'] }));
  const bubbleColors = mine ? { bg: colors.accent, fg: colors.accentForeground } : { bg: colors.card, fg: colors.foreground };

  const a11yLabel = tombstone
    ? `${mine ? t('mobile.messaging.label.you') : author}. ${message.status === 'HIDDEN' ? t('mobile.messaging.message.hidden') : t('mobile.messaging.message.deleted')}. ${time}`
    : [
        mine ? t('mobile.messaging.label.you') : author,
        text,
        images.length > 0 ? t('mobile.messaging.message.photos', { count: images.length }) : null,
        message.editedAt ? t('mobile.messaging.message.edited') : null,
        seen ? t('mobile.messaging.message.seen') : null,
        time,
      ]
        .filter(Boolean)
        .join('. ');

  const body = (
    <View
      style={{
        maxWidth: '86%',
        alignSelf: mine ? 'flex-end' : 'flex-start',
        gap: 4,
        padding: spacing[3],
        borderRadius: radii.xl,
        backgroundColor: tombstone ? 'transparent' : bubbleColors.bg,
        borderWidth: tombstone ? 1 : mine ? 0 : 1,
        borderStyle: tombstone ? 'dashed' : 'solid',
        borderColor: colors.border,
      }}>
      {!mine ? (
        <Text variant="caption" tone="primary" style={{ fontWeight: '700' }} numberOfLines={1}>
          {author}
        </Text>
      ) : null}
      {replyTo && !tombstone ? (
        <View style={{ borderStartWidth: 3, borderStartColor: colors.primary, paddingStart: 8 }}>
          <Text variant="caption" numberOfLines={1} style={{ fontWeight: '600' }}>
            {t('mobile.messaging.message.replyTo', { name: replyTo.author })}
          </Text>
          <Text variant="caption" numberOfLines={1}>
            {replyTo.snippet ?? t('mobile.messaging.message.replyEarlier')}
          </Text>
        </View>
      ) : null}
      {tombstone ? (
        <Text tone="muted" style={{ fontStyle: 'italic' }}>
          {message.status === 'HIDDEN' ? t('mobile.messaging.message.hidden') : t('mobile.messaging.message.deleted')}
        </Text>
      ) : (
        <>
          {media.length > 0 ? (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 4 }}>
              {media.map((item, index) => {
                const size = media.length === 1 ? 220 : 108;
                return (
                  <Pressable
                    key={item.mediaId}
                    accessibilityRole="imagebutton"
                    accessibilityLabel={t('mobile.messaging.message.photoLabel', { n: index + 1, total: media.length })}
                    onPress={() => setViewing(index)}
                    style={{ width: size, height: size, borderRadius: radii.lg, overflow: 'hidden' }}>
                    <FeedImage
                      mediaId={item.mediaId}
                      available={item.variants}
                      variant={media.length === 1 ? 'SMALL' : 'THUMB'}
                      label={t('mobile.messaging.message.photoLabel', { n: index + 1, total: media.length })}
                      style={{ width: size, height: size }}
                    />
                  </Pressable>
                );
              })}
            </View>
          ) : null}
          {text ? <LinkedText text={text} style={{ color: bubbleColors.fg }} /> : null}
          {message.reactions.length > 0 ? (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 2 }}>
              {message.reactions.map((reaction) => (
                <Pressable
                  key={reaction.emoji}
                  accessibilityRole="button"
                  accessibilityLabel={t('mobile.messaging.message.reactionLabel', { emoji: reaction.emoji, count: reaction.count })}
                  accessibilityState={{ selected: reaction.mine }}
                  disabled={!canReact}
                  onPress={() => onToggleReaction(reaction.emoji, reaction.mine)}
                  style={{
                    minHeight: 32,
                    paddingHorizontal: 8,
                    borderRadius: radii.full,
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: 4,
                    backgroundColor: reaction.mine ? colors.primary : colors.muted,
                  }}>
                  <Text>{reaction.emoji}</Text>
                  <Text variant="caption" style={{ color: reaction.mine ? colors.primaryForeground : colors.foreground }}>
                    {reaction.count}
                  </Text>
                </Pressable>
              ))}
            </View>
          ) : null}
        </>
      )}
      <View style={{ flexDirection: 'row', gap: 8, alignSelf: mine ? 'flex-end' : 'flex-start' }}>
        {message.editedAt && !tombstone ? <Text variant="caption">{t('mobile.messaging.message.edited')}</Text> : null}
        <Text variant="caption">{time}</Text>
        {seen ? <Text variant="caption" tone="primary">{t('mobile.messaging.message.seen')}</Text> : null}
      </View>
    </View>
  );

  return (
    <View style={{ paddingHorizontal: spacing[4], paddingVertical: 3 }}>
      {onOpenOptions ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={a11yLabel}
          accessibilityHint={t('mobile.messaging.message.optionsHint')}
          onPress={onOpenOptions}
          onLongPress={onOpenOptions}
          style={{ minHeight: minTouchTarget - 8 }}>
          {body}
        </Pressable>
      ) : (
        <View accessible accessibilityLabel={a11yLabel}>
          {body}
        </View>
      )}
      {viewing !== null ? <PhotoViewer media={media} startIndex={viewing} onClose={() => setViewing(null)} /> : null}
    </View>
  );
}

export const MessageBubble = memo(Bubble);

/**
 * A message that has not reached the server yet. It stays in the conversation (in memory only), is retried by itself with the same
 * id, and can be retried at once or deleted. The text is the person's own; the problem is the API's localized `detail`.
 */
function PendingBubble({ entry, onRetry, onDiscard }: { entry: OutboxEntry; onRetry: () => void; onDiscard: () => void }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const failed = entry.status === 'failed';
  const sending = entry.status === 'sending' || entry.status === 'queued' || entry.status === 'sent';
  const statusText = sending
    ? t('mobile.messaging.pending.sending')
    : failed
      ? entry.gaveUp || !entry.problem?.detail
        ? t('mobile.messaging.pending.failed')
        : entry.problem.detail
      : t('mobile.messaging.pending.retrying');
  return (
    <View style={{ paddingHorizontal: spacing[4], paddingVertical: 3 }}>
      <View
        style={{
          maxWidth: '86%',
          alignSelf: 'flex-end',
          gap: 6,
          padding: spacing[3],
          borderRadius: radii.xl,
          backgroundColor: colors.accent,
          borderWidth: sending ? 0 : 1,
          borderColor: colors.destructive,
          opacity: sending ? 0.7 : 1,
        }}>
        {entry.body.length > 0 ? <LinkedText text={entry.body} style={{ color: colors.accentForeground }} /> : null}
        {entry.mediaIds.length > 0 ? <Text variant="caption">{t('mobile.messaging.message.photos', { count: entry.mediaIds.length })}</Text> : null}
        <Text variant="caption" tone={sending ? 'muted' : 'destructive'} accessibilityLiveRegion="polite">
          {statusText}
        </Text>
        {!sending ? (
          <View style={{ flexDirection: 'row', gap: spacing[3] }}>
            <Pressable accessibilityRole="button" accessibilityLabel={t('mobile.messaging.pending.retryA11y')} onPress={onRetry} style={{ minHeight: 44, justifyContent: 'center' }}>
              <Text variant="label" tone="primary">
                {t('mobile.messaging.pending.retry')}
              </Text>
            </Pressable>
            <Pressable accessibilityRole="button" accessibilityLabel={t('mobile.messaging.pending.discardA11y')} onPress={onDiscard} style={{ minHeight: 44, justifyContent: 'center' }}>
              <Text variant="label" tone="destructive">
                {t('mobile.messaging.pending.discard')}
              </Text>
            </Pressable>
          </View>
        ) : null}
      </View>
    </View>
  );
}

export const PendingMessage = memo(PendingBubble);
