import { SymbolView } from 'expo-symbols';
import { useMemo, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { authorLabel } from '@/lib/feed/post-model';
import { liveStories, remaining } from '@/lib/feed/story-timing';
import { useClock } from '@/lib/feed/use-clock';
import type { StoryView } from '@/lib/feed/types';
import { avatarColor, initialsOf } from '@/lib/people/avatar';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from '../ui';

import { FeedImage } from './feed-image';
import { StoryViewer } from './story-viewer';

const SIZE = 68;

/**
 * The row of stories on top of a feed: an "add" tile for those who may post one, then a round thumbnail per live story with a
 * ring that is bold for a story you have not seen. A tap opens the viewer at that story and carries on with the ones after it.
 * Stories last 24 hours; an expired one is dropped here even before the server's cleanup runs.
 */
export function StoryStrip({ stories, onAdd, loading }: { stories: readonly StoryView[] | undefined; onAdd?: (() => void) | undefined; loading?: boolean }) {
  const t = useTranslations();
  const { spacing } = useAppTheme();
  const [open, setOpen] = useState<number | null>(null);
  const now = useClock();
  const live = useMemo(() => liveStories(stories ?? [], now), [stories, now]);

  if (!onAdd && live.length === 0) return null;
  return (
    <View style={{ gap: spacing[2] }} accessibilityRole="summary" accessibilityLabel={t('mobile.feed.story.stripLabel')}>
      <FlatList
        horizontal
        data={live}
        keyExtractor={(story) => story.id}
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: spacing[3], paddingVertical: spacing[1] }}
        ListHeaderComponent={onAdd ? <AddTile onPress={onAdd} /> : undefined}
        ListHeaderComponentStyle={{ marginEnd: spacing[3] }}
        renderItem={({ item, index }) => <StoryTile story={item} now={now} onPress={() => setOpen(index)} />}
        ListEmptyComponent={loading ? undefined : onAdd ? <Text variant="caption" style={{ alignSelf: 'center' }}>{t('mobile.feed.story.none')}</Text> : undefined}
      />
      {open !== null ? <StoryViewer stories={live} startIndex={open} onClose={() => setOpen(null)} /> : null}
    </View>
  );
}

function AddTile({ onPress }: { onPress: () => void }) {
  const t = useTranslations();
  const { colors } = useAppTheme();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={t('mobile.feed.story.add')} onPress={onPress} style={{ width: SIZE + 12, alignItems: 'center', gap: 4 }}>
      <View style={{ width: SIZE, height: SIZE, borderRadius: SIZE / 2, borderWidth: 2, borderStyle: 'dashed', borderColor: colors.primary, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.accent }}>
        <SymbolView name={{ ios: 'plus', android: 'add', web: 'add' }} size={28} tintColor={colors.primary} />
      </View>
      <Text variant="caption" numberOfLines={1}>
        {t('mobile.feed.story.addShort')}
      </Text>
    </Pressable>
  );
}

function StoryTile({ story, now, onPress }: { story: StoryView; now: number; onPress: () => void }) {
  const t = useTranslations();
  const { colors } = useAppTheme();
  const label = authorLabel(story.author);
  const author = label.kind === 'name' ? label.name : t(`mobile.feed.author.${label.role}`);
  const left = remaining(story.expiresAt, now);
  const leftText = left.unit === 'soon' ? t('mobile.feed.story.endsSoon') : left.unit === 'minutes' ? t('mobile.feed.story.minutesLeft', { value: left.value }) : t('mobile.feed.story.hoursLeft', { value: left.value });
  const unseen = !story.viewedByMe;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={t('mobile.feed.story.tileLabel', { author, state: unseen ? t('mobile.feed.story.new') : t('mobile.feed.story.seen'), left: leftText })}
      onPress={onPress}
      style={{ width: SIZE + 12, alignItems: 'center', gap: 4 }}>
      <View style={{ width: SIZE, height: SIZE, borderRadius: SIZE / 2, padding: 3, borderWidth: unseen ? 3 : 2, borderColor: unseen ? colors.primary : colors.border }}>
        {story.media ? (
          <FeedImage mediaId={story.media.mediaId} available={story.media.variants} variant="THUMB" label={author} style={{ flex: 1, borderRadius: SIZE / 2 }} />
        ) : (
          <View style={{ flex: 1, borderRadius: SIZE / 2, backgroundColor: avatarColor(story.id), alignItems: 'center', justifyContent: 'center' }}>
            <Text variant="label" allowFontScaling={false} style={{ color: colors.primaryForeground }}>
              {initialsOf(author, 1) || '?'}
            </Text>
          </View>
        )}
      </View>
      <Text variant="caption" numberOfLines={1} style={{ maxWidth: SIZE + 8 }}>
        {author}
      </Text>
    </Pressable>
  );
}
