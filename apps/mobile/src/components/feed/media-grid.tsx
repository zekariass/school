import { useState } from 'react';
import { Pressable, View, type StyleProp, type ViewStyle } from 'react-native';
import { useTranslations } from 'use-intl';

import { TRIO_SPLIT, aspectOf, cellWidths, collageLayout, listVariant } from '@/lib/feed/media-layout';
import type { FeedMediaItem } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { FeedImage } from './feed-image';
import { PhotoViewer } from './photo-viewer';

const GAP = 4;

/**
 * A post's photos as the collage of the design: one wide photo, two side by side, or one large photo with two stacked beside it
 * and a "+N" on the last when there are more. Small cells ask for the THUMB variant, big ones SMALL; the full-size MEDIUM
 * variant is only fetched by the viewer a tap opens. No video, nothing plays by itself.
 */
export function MediaGrid({ media, width }: { media: readonly FeedMediaItem[]; width: number }) {
  const t = useTranslations();
  const { colors, radii } = useAppTheme();
  const [open, setOpen] = useState<number | null>(null);
  if (media.length === 0 || width <= 0) return null;

  const collage = collageLayout(media.length, aspectOf(media[0]!));
  const widths = cellWidths(collage, width, GAP);
  const height = width / collage.aspect;
  const lastCell = collage.cells.length - 1;

  const cell = (position: number, style?: StyleProp<ViewStyle>) => {
    const index = collage.cells[position]!;
    const item = media[index]!;
    const label = item.caption?.trim() || t('mobile.feed.photoLabel', { n: index + 1, total: media.length });
    return (
      <Pressable
        key={item.mediaId}
        accessibilityRole="imagebutton"
        accessibilityLabel={collage.more > 0 && position === lastCell ? `${label}. ${t('mobile.feed.morePhotos', { count: collage.more })}` : label}
        accessibilityHint={t('mobile.feed.openPhotoHint')}
        onPress={() => setOpen(index)}
        style={[{ borderRadius: radii.md, overflow: 'hidden' }, style]}>
        <FeedImage mediaId={item.mediaId} available={item.variants} variant={listVariant(widths[position] ?? width)} label={label} style={{ flex: 1 }} />
        {collage.more > 0 && position === lastCell ? (
          <View
            style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: colors.foreground, opacity: 0.55, alignItems: 'center', justifyContent: 'center' }}>
            <Text variant="title" style={{ color: colors.background }}>
              {t('mobile.feed.morePhotos', { count: collage.more })}
            </Text>
          </View>
        ) : null}
      </Pressable>
    );
  };

  return (
    <View style={{ height, flexDirection: 'row', gap: GAP }}>
      {collage.shape === 'single' ? cell(0, { flex: 1 }) : null}
      {collage.shape === 'pair' ? (
        <>
          {cell(0, { flex: 1 })}
          {cell(1, { flex: 1 })}
        </>
      ) : null}
      {collage.shape === 'trio' ? (
        <>
          {cell(0, { flex: TRIO_SPLIT.main })}
          <View style={{ flex: TRIO_SPLIT.side, gap: GAP }}>
            {cell(1, { flex: 1 })}
            {cell(2, { flex: 1 })}
          </View>
        </>
      ) : null}
      {open !== null ? <PhotoViewer media={media} startIndex={open} onClose={() => setOpen(null)} /> : null}
    </View>
  );
}
