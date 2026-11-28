import { Image } from 'expo-image';
import { View } from 'react-native';

import { useMediaUrl } from '@/lib/media/use-media';
import { avatarColor, initialsOf } from '@/lib/people/avatar';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './ui';

export interface AvatarProps {
  mediaId?: string | null | undefined;
  name: string;
  size?: number;
  accessibilityLabel?: string;
  /** Picks the placeholder colour (the person's id); the name is used when absent. */
  seed?: string | undefined;
  /** The student's `avatarConfig`: a `color` (`#rrggbb`) in it sets the placeholder colour. */
  config?: Record<string, unknown> | null | undefined;
  /** Letters on the placeholder (1 or 2). */
  maxInitials?: 1 | 2;
  /** Keep the picture in memory only, never on disk (children's photos shown in the feed). */
  memoryOnly?: boolean;
}

/**
 * A round picture: the signed-link photo when there is one, otherwise a colourful placeholder
 * with initials (same colour every time for the same person, so a class wall looks friendly and
 * stable). The label is the person's name for screen readers.
 */
export function Avatar({ mediaId, name, size = 96, accessibilityLabel, seed, config, maxInitials = 2, memoryOnly = false }: AvatarProps) {
  const { colors } = useAppTheme();
  const url = useMediaUrl(mediaId, size > 160 ? 'MEDIUM' : 'SMALL');
  const frame = { width: size, height: size, borderRadius: size / 2 };

  if (mediaId && url.data) {
    return (
      <Image
        // Cache by media id: the signed link changes every few minutes, the picture does not.
        source={{ uri: url.data.url, cacheKey: `media-${mediaId}` }}
        cachePolicy={memoryOnly ? 'memory' : 'disk'}
        accessibilityLabel={accessibilityLabel ?? name}
        accessible
        contentFit="cover"
        transition={150}
        style={[frame, { backgroundColor: colors.muted }]}
      />
    );
  }
  const initials = initialsOf(name, maxInitials) || '?';
  return (
    <View
      accessible
      accessibilityLabel={accessibilityLabel ?? name}
      style={[frame, { backgroundColor: avatarColor(seed ?? name, config), alignItems: 'center', justifyContent: 'center' }]}>
      <Text
        variant="title"
        allowFontScaling={false}
        style={{ color: '#FFFFFF', fontSize: size / (initials.length > 1 ? 2.8 : 2.2), lineHeight: size / 1.9 }}>
        {initials}
      </Text>
    </View>
  );
}
