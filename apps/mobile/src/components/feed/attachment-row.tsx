import { Image } from 'expo-image';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { MAX_CAPTION_CHARS, type Attachment } from '@/lib/feed/composer';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Button, Text, TextField } from '../ui';

import { FeedImage } from './feed-image';

/**
 * One photo of the post being written: its preview (from the device, or the signed link of a photo already on the post), the
 * state of its upload with a progress bar, a retry when it failed, a short description for people who cannot see it, and the
 * buttons to move or remove it.
 */
export function AttachmentRow({
  attachment,
  index,
  total,
  onCaption,
  onRetry,
  onRemove,
  onMove,
}: {
  attachment: Attachment;
  index: number;
  total: number;
  onCaption: (value: string) => void;
  onRetry: () => void;
  onRemove: () => void;
  onMove: (direction: -1 | 1) => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const busy = attachment.status === 'preparing' || attachment.status === 'uploading';
  const status =
    attachment.status === 'preparing'
      ? t('media.upload.preparing')
      : attachment.status === 'uploading'
        ? t('media.upload.uploading', { percent: attachment.percent })
        : attachment.status === 'failed'
          ? (attachment.failure ?? t('media.errors.network'))
          : t('mobile.feed.photo.ready');

  return (
    <View style={{ gap: spacing[2], padding: spacing[3], borderRadius: radii.xl, backgroundColor: colors.card, borderWidth: 1, borderColor: attachment.status === 'failed' ? colors.destructive : colors.border }}>
      <View style={{ flexDirection: 'row', gap: spacing[3] }}>
        {attachment.uri ? (
          <Image
            source={{ uri: attachment.uri }}
            cachePolicy="none"
            contentFit="cover"
            accessibilityLabel={attachment.caption.trim() || t('mobile.feed.photoLabel', { n: index + 1, total })}
            style={{ width: 88, height: 88, borderRadius: radii.lg, backgroundColor: colors.muted }}
          />
        ) : attachment.mediaId ? (
          <FeedImage
            mediaId={attachment.mediaId}
            available={['THUMB', 'SMALL']}
            variant="THUMB"
            label={attachment.caption.trim() || t('mobile.feed.photoLabel', { n: index + 1, total })}
            style={{ width: 88, height: 88, borderRadius: radii.lg }}
          />
        ) : null}
        <View style={{ flex: 1, gap: spacing[2], justifyContent: 'center' }}>
          <View
            accessibilityRole="progressbar"
            accessibilityLabel={t('media.upload.progressLabel')}
            accessibilityValue={attachment.status === 'uploading' ? { min: 0, max: 100, now: attachment.percent } : {}}
            style={{ height: 8, borderRadius: 4, backgroundColor: colors.muted, overflow: 'hidden' }}>
            <View
              style={{
                height: 8,
                width: attachment.status === 'uploading' ? `${attachment.percent}%` : '100%',
                backgroundColor: attachment.status === 'failed' ? colors.destructive : colors.primary,
                opacity: attachment.status === 'preparing' ? 0.4 : 1,
              }}
            />
          </View>
          <Text variant="caption" tone={attachment.status === 'failed' ? 'destructive' : 'muted'} accessibilityLiveRegion="polite">
            {status}
          </Text>
        </View>
      </View>
      <TextField
        label={t('mobile.feed.photo.caption')}
        value={attachment.caption}
        onChangeText={onCaption}
        maxLength={MAX_CAPTION_CHARS}
        placeholder={t('mobile.feed.photo.captionPlaceholder')}
        hint={t('mobile.feed.photo.captionHint')}
      />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {attachment.status === 'failed' && attachment.uri ? <Button variant="secondary" fullWidth={false} label={t('common.retry')} onPress={onRetry} /> : null}
        {index > 0 ? <Button variant="ghost" fullWidth={false} label={t('mobile.feed.photo.earlier')} disabled={busy} onPress={() => onMove(-1)} /> : null}
        {index < total - 1 ? <Button variant="ghost" fullWidth={false} label={t('mobile.feed.photo.later')} disabled={busy} onPress={() => onMove(1)} /> : null}
        <Button variant="destructive" fullWidth={false} label={t('mobile.feed.photo.remove')} onPress={onRemove} />
      </View>
    </View>
  );
}
