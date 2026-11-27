import { Image } from 'expo-image';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import type { Attachment } from '@/lib/media/attachments';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { FeedImage } from '../feed/feed-image';
import { Button, Text } from '../ui';

/**
 * One picture of the item being made: its preview (from the phone, or the signed link of a picture already on the item), the
 * state of its upload with a progress bar, a retry when it failed, and the buttons to move or remove it.
 */
export function FileRow({
  attachment,
  index,
  total,
  onRetry,
  onRemove,
  onMove,
}: {
  attachment: Attachment;
  index: number;
  total: number;
  onRetry: () => void;
  onRemove: () => void;
  onMove: (direction: -1 | 1) => void;
}) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const busy = attachment.status === 'preparing' || attachment.status === 'uploading';
  const label = t('mobile.portfolio.file.label', { n: index + 1, total });
  const status =
    attachment.status === 'preparing'
      ? t('media.upload.preparing')
      : attachment.status === 'uploading'
        ? t('media.upload.uploading', { percent: attachment.percent })
        : attachment.status === 'failed'
          ? (attachment.failure ?? t('media.errors.network'))
          : t('mobile.portfolio.file.ready');

  return (
    <View style={{ gap: spacing[2], padding: spacing[3], borderRadius: radii.xl, backgroundColor: colors.card, borderWidth: 1, borderColor: attachment.status === 'failed' ? colors.destructive : colors.border }}>
      <View style={{ flexDirection: 'row', gap: spacing[3] }}>
        {attachment.uri ? (
          <Image
            source={{ uri: attachment.uri }}
            cachePolicy="none"
            contentFit="cover"
            accessibilityLabel={label}
            style={{ width: 88, height: 88, borderRadius: radii.lg, backgroundColor: colors.muted }}
          />
        ) : attachment.mediaId ? (
          <FeedImage mediaId={attachment.mediaId} available={['THUMB', 'SMALL']} variant="THUMB" label={label} style={{ width: 88, height: 88, borderRadius: radii.lg }} />
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
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
        {attachment.status === 'failed' && attachment.uri ? <Button variant="secondary" fullWidth={false} label={t('common.retry')} onPress={onRetry} /> : null}
        {index > 0 ? <Button variant="ghost" fullWidth={false} label={t('mobile.portfolio.file.earlier')} disabled={busy} onPress={() => onMove(-1)} /> : null}
        {index < total - 1 ? <Button variant="ghost" fullWidth={false} label={t('mobile.portfolio.file.later')} disabled={busy} onPress={() => onMove(1)} /> : null}
        <Button variant="destructive" fullWidth={false} label={t('mobile.portfolio.file.remove')} onPress={onRemove} />
      </View>
    </View>
  );
}
