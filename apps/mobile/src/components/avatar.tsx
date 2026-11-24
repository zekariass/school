import { Image } from 'expo-image';
import { View } from 'react-native';

import { useMediaUrl } from '@/lib/media/use-media';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './ui';

export interface AvatarProps {
  mediaId?: string | null | undefined;
  name: string;
  size?: number;
  accessibilityLabel?: string;
}

/** A round profile photo fetched through a signed link, or the person's initial when there is none. */
export function Avatar({ mediaId, name, size = 96, accessibilityLabel }: AvatarProps) {
  const { colors } = useAppTheme();
  const url = useMediaUrl(mediaId, size > 160 ? 'MEDIUM' : 'SMALL');
  const initial = name.trim().charAt(0).toUpperCase();
  const frame = { width: size, height: size, borderRadius: size / 2 };

  if (mediaId && url.data) {
    return (
      <Image
        // Cache by media id: the signed link changes every few minutes, the picture does not.
        source={{ uri: url.data.url, cacheKey: `media-${mediaId}` }}
        accessibilityLabel={accessibilityLabel ?? name}
        accessible
        contentFit="cover"
        transition={150}
        style={[frame, { backgroundColor: colors.muted }]}
      />
    );
  }
  return (
    <View
      accessible
      accessibilityLabel={accessibilityLabel ?? name}
      style={[frame, { backgroundColor: colors.muted, alignItems: 'center', justifyContent: 'center' }]}>
      <Text variant="title" tone="muted" style={{ fontSize: size / 2.4, lineHeight: size / 2 }}>
        {initial}
      </Text>
    </View>
  );
}
