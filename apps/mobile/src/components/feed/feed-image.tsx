import { Image } from 'expo-image';
import { SymbolView } from 'expo-symbols';
import { View, type StyleProp, type ViewStyle } from 'react-native';

import { useMediaUrl } from '@/lib/media/use-media';
import { pickVariant, type Variant } from '@/lib/feed/media-layout';
import { useReduceMotion } from '@/lib/use-reduce-motion';
import { useAppTheme } from '@/lib/theme/theme-provider';

export interface FeedImageProps {
  mediaId: string;
  /** The variants the item has (from the payload); the nearest to `variant` is asked for. Lists use THUMB/SMALL, the viewer MEDIUM. */
  available: readonly string[];
  variant: Variant;
  /** Read by screen readers: the caption, or "Photo 2 of 4". */
  label: string;
  contentFit?: 'cover' | 'contain';
  style?: StyleProp<ViewStyle>;
}

/**
 * A photo of the feed. The signed link comes from `GET /media/{id}/url` (cached about four minutes, never persisted) and the
 * picture itself is cached in memory only (`cachePolicy="memory"`): a child's photo must not end up in a disk cache. While it
 * loads, or when the API says no (consent withdrawn: 404), a quiet placeholder takes its place.
 */
export function FeedImage({ mediaId, available, variant, label, contentFit = 'cover', style }: FeedImageProps) {
  const { colors } = useAppTheme();
  const reduceMotion = useReduceMotion();
  const chosen = pickVariant(available, variant) ?? variant;
  const url = useMediaUrl(mediaId, chosen);

  return (
    <View accessible accessibilityRole="image" accessibilityLabel={label} style={[{ backgroundColor: colors.muted, overflow: 'hidden' }, style]}>
      {url.data ? (
        <Image
          source={{ uri: url.data.url, cacheKey: `feed-${mediaId}-${chosen}` }}
          cachePolicy="memory"
          contentFit={contentFit}
          transition={reduceMotion ? 0 : 150}
          recyclingKey={`${mediaId}-${chosen}`}
          accessible={false}
          style={{ width: '100%', height: '100%' }}
        />
      ) : url.isError ? (
        <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          <SymbolView name={{ ios: 'photo', android: 'image', web: 'image' }} size={28} tintColor={colors.mutedForeground} />
        </View>
      ) : null}
    </View>
  );
}
