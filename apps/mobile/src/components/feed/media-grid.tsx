import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { aspectOf, gridLayout, listVariant } from '@/lib/feed/media-layout';
import type { FeedMediaItem } from '@/lib/feed/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { FeedImage } from './feed-image';
import { PhotoViewer } from './photo-viewer';

const GAP = 3;

/**
 * A post's photos as a tidy grid (one wide photo, two, three, or 2 x 2 with "+N"). Small cells ask for the THUMB variant,
 * big ones SMALL; the full-size MEDIUM variant is only fetched by the viewer a tap opens. No video, nothing plays by itself.
 */
export function MediaGrid({ media, width }: { media: readonly FeedMediaItem[]; width: number }) {
  const t = useTranslations();
  const { colors, radii } = useAppTheme();
  const [open, setOpen] = useState<number | null>(null);
  if (media.length === 0 || width <= 0) return null;

  const layout = gridLayout(media.length, aspectOf(media[0]!));
  return (
    <View style={{ gap: GAP, borderRadius: radii.xl, overflow: 'hidden' }}>
      {layout.rows.map((row, rowIndex) => {
        const cellWidth = (width - GAP * (row.cells.length - 1)) / row.cells.length;
        return (
          <View key={rowIndex} style={{ flexDirection: 'row', gap: GAP, height: width / row.aspect }}>
            {row.cells.map((cell) => {
              const item = media[cell.index]!;
              const label = item.caption?.trim() || t('mobile.feed.photoLabel', { n: cell.index + 1, total: media.length });
              const last = rowIndex === layout.rows.length - 1 && cell === row.cells[row.cells.length - 1];
              return (
                <Pressable
                  key={item.mediaId}
                  accessibilityRole="imagebutton"
                  accessibilityLabel={label}
                  accessibilityHint={t('mobile.feed.openPhotoHint')}
                  onPress={() => setOpen(cell.index)}
                  style={{ flex: 1 }}>
                  <FeedImage
                    mediaId={item.mediaId}
                    available={item.variants}
                    variant={listVariant(cellWidth)}
                    label={label}
                    style={{ flex: 1 }}
                  />
                  {last && layout.more > 0 ? (
                    <View
                      style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: colors.foreground, opacity: 0.55, alignItems: 'center', justifyContent: 'center' }}>
                      <Text variant="title" style={{ color: colors.background }}>
                        {t('mobile.feed.morePhotos', { count: layout.more })}
                      </Text>
                    </View>
                  ) : null}
                </Pressable>
              );
            })}
          </View>
        );
      })}
      {open !== null ? <PhotoViewer media={media} startIndex={open} onClose={() => setOpen(null)} /> : null}
    </View>
  );
}
