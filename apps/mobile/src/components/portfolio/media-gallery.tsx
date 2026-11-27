import { SymbolView } from 'expo-symbols';
import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { aspectOf, clampAspect } from '@/lib/feed/media-layout';
import type { PortfolioMedia } from '@/lib/portfolio/types';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { FeedImage } from '../feed/feed-image';
import { Text } from '../ui';

/**
 * The pictures of an item, one under the other at the width of the screen (MEDIUM variants, signed links never stored). Only
 * what the API sent is shown: a withheld item arrives with no files. A PDF has no preview on the phone.
 */
export function MediaGallery({ media, title }: { media: readonly PortfolioMedia[]; title: string }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const files = [...media].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  if (files.length === 0) return null;
  return (
    <View style={{ gap: spacing[3] }}>
      {files.map((file, index) =>
        file.kind === 'IMAGE' ? (
          <FeedImage
            key={file.mediaId}
            mediaId={file.mediaId}
            available={file.variants}
            variant="MEDIUM"
            contentFit="contain"
            label={files.length > 1 ? `${title}, ${t('mobile.portfolio.file.label', { n: index + 1, total: files.length })}` : title}
            style={{ width: '100%', aspectRatio: clampAspect(aspectOf(file)), borderRadius: radii.xl }}
          />
        ) : (
          <View
            key={file.mediaId}
            accessible
            accessibilityLabel={t('mobile.portfolio.file.document')}
            style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3], padding: spacing[4], borderRadius: radii.xl, backgroundColor: colors.muted }}>
            <SymbolView name={{ ios: 'doc.richtext', android: 'description', web: 'description' }} size={32} tintColor={colors.mutedForeground} />
            <View style={{ flex: 1 }}>
              <Text variant="label">{t('mobile.portfolio.file.document')}</Text>
              <Text variant="caption">{t('mobile.portfolio.file.documentHint')}</Text>
            </View>
          </View>
        ),
      )}
    </View>
  );
}
