import { colors as tokens } from '@school/design-tokens';
import { SymbolView } from 'expo-symbols';
import { useRef, useState } from 'react';
import { FlatList, Modal, Pressable, ScrollView, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import type { FeedMediaItem } from '@/lib/feed/types';

import { Text } from '../ui';

import { FeedImage } from './feed-image';

/** The viewer is always dark, like a cinema, whatever the app theme. */
const dark = tokens.dark;

/**
 * Full-screen photos: swipe sideways between them, pinch to zoom on iOS. This is the only place that asks for the MEDIUM
 * variant. Close with the button, the system back gesture or the hardware back key.
 */
export function PhotoViewer({ media, startIndex, onClose }: { media: readonly FeedMediaItem[]; startIndex: number; onClose: () => void }) {
  const t = useTranslations();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const [index, setIndex] = useState(startIndex);
  const list = useRef<FlatList<FeedMediaItem>>(null);
  const current = media[index];

  return (
    <Modal visible animationType="fade" onRequestClose={onClose} supportedOrientations={['portrait', 'landscape']}>
      <View style={{ flex: 1, backgroundColor: dark.background }} accessibilityViewIsModal>
        <FlatList
          ref={list}
          data={media as FeedMediaItem[]}
          horizontal
          pagingEnabled
          showsHorizontalScrollIndicator={false}
          initialScrollIndex={startIndex}
          getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
          keyExtractor={(item) => item.mediaId}
          onMomentumScrollEnd={(event) => setIndex(Math.round(event.nativeEvent.contentOffset.x / Math.max(1, width)))}
          windowSize={3}
          renderItem={({ item, index: i }) => (
            <ScrollView
              style={{ width, height }}
              maximumZoomScale={3}
              minimumZoomScale={1}
              centerContent
              showsHorizontalScrollIndicator={false}
              showsVerticalScrollIndicator={false}
              contentContainerStyle={{ width, height, alignItems: 'center', justifyContent: 'center' }}>
              <FeedImage
                mediaId={item.mediaId}
                available={item.variants}
                variant="MEDIUM"
                contentFit="contain"
                label={item.caption?.trim() || t('mobile.feed.photoLabel', { n: i + 1, total: media.length })}
                style={{ width, height: height * 0.8, backgroundColor: 'transparent' }}
              />
            </ScrollView>
          )}
        />
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={t('mobile.feed.close')}
          onPress={onClose}
          hitSlop={8}
          style={{ position: 'absolute', top: insets.top + 8, end: 16, width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', backgroundColor: dark.card }}>
          <SymbolView name={{ ios: 'xmark', android: 'close', web: 'close' }} size={20} tintColor={dark.foreground} />
        </Pressable>
        <View pointerEvents="none" style={{ position: 'absolute', bottom: insets.bottom + 16, left: 16, right: 16, gap: 4, alignItems: 'center' }}>
          {current?.caption ? (
            <Text style={{ color: dark.foreground, textAlign: 'center' }} accessibilityLiveRegion="polite">
              {current.caption}
            </Text>
          ) : null}
          {media.length > 1 ? (
            <Text variant="caption" style={{ color: dark.mutedForeground }}>
              {t('mobile.feed.photoCounter', { n: index + 1, total: media.length })}
            </Text>
          ) : null}
        </View>
      </View>
    </Modal>
  );
}
