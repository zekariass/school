import { colors as tokens } from '@school/design-tokens';
import { useQueryClient } from '@tanstack/react-query';
import { SymbolView } from 'expo-symbols';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Alert, Modal, PanResponder, Pressable, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { FEED_ROOT } from '@/lib/feed/keys';
import { authorLabel } from '@/lib/feed/post-model';
import { deleteStory, markStoryViewed } from '@/lib/feed/requests';
import {
  durationOf,
  liveStories,
  next,
  previous,
  remaining,
  segmentFill,
  setPaused,
  startAt,
  tapZone,
  tick,
  type PlayerState,
} from '@/lib/feed/story-timing';
import type { StoryView } from '@/lib/feed/types';
import { useClock } from '@/lib/feed/use-clock';
import { useReduceMotion } from '@/lib/use-reduce-motion';

import { Text } from '../ui';

import { FeedImage } from './feed-image';

/** Always dark, like the photo viewer. */
const dark = tokens.dark;

const TICK_MS = 100;

/** Stories viewed in this session (ids only): one "view" call each; the API counts a viewer once anyway. */
const viewed = new Set<string>();

/**
 * The full-screen story viewer. Tap the right side for the next story and the left side for the previous one, press and hold to
 * pause, swipe down to close. Photos advance by themselves after six seconds, except with "reduce motion" or a screen reader
 * on: then nothing moves until you tap, and the buttons below do the same. Each story is marked as seen once. A story that
 * expires while the viewer is open is skipped. Photos ask for the MEDIUM variant here and nowhere else.
 */
export function StoryViewer({ stories: all, startIndex, onClose }: { stories: readonly StoryView[]; startIndex: number; onClose: () => void }) {
  const t = useTranslations();
  const queryClient = useQueryClient();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const reduceMotion = useReduceMotion();
  const clock = useClock();
  const [screenReader, setScreenReader] = useState(false);
  const [removed, setRemoved] = useState<ReadonlySet<string>>(new Set());
  // The list is fixed when the viewer opens (what was live then and not deleted since).
  const [now] = useState(() => Date.now());
  const stories = useMemo(() => liveStories(all, now).filter((story) => !removed.has(story.id)), [all, now, removed]);
  const opened = useMemo(() => all[startIndex]?.id, [all, startIndex]);
  const [player, setPlayer] = useState<PlayerState>(() => startAt(Math.max(0, liveStories(all, now).findIndex((story) => story.id === opened))));
  const holding = useRef(false);

  useEffect(() => {
    let alive = true;
    void AccessibilityInfo.isScreenReaderEnabled().then((value) => alive && setScreenReader(value));
    const subscription = AccessibilityInfo.addEventListener('screenReaderChanged', setScreenReader);
    return () => {
      alive = false;
      subscription.remove();
    };
  }, []);

  const story = stories[Math.min(player.index, stories.length - 1)];
  const automatic = !reduceMotion && !screenReader;

  // Time passes (only for people who want it to).
  const latest = useRef(player);
  useEffect(() => {
    latest.current = player;
  }, [player]);
  useEffect(() => {
    if (!automatic || !story) return;
    let last = Date.now();
    const timer = setInterval(() => {
      const current = Date.now();
      const delta = current - last;
      last = current;
      const state = latest.current;
      const target = stories[state.index];
      const result = tick(state, delta, stories.length, target ? durationOf(target) : 0);
      if (result.finished) onClose();
      else if (result.state !== state) setPlayer(result.state);
    }, TICK_MS);
    return () => clearInterval(timer);
  }, [automatic, story, stories, onClose]);

  // One "seen" per story.
  useEffect(() => {
    if (!story || viewed.has(story.id)) return;
    viewed.add(story.id);
    markStoryViewed(story.schoolId, story.id)
      .then(() => queryClient.invalidateQueries({ queryKey: [FEED_ROOT, 'stories'] }))
      .catch(() => viewed.delete(story.id));
  }, [story, queryClient]);

  // Nothing left (all expired or deleted).
  useEffect(() => {
    if (stories.length === 0) onClose();
  }, [stories.length, onClose]);

  const swipe = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_event, gesture) => gesture.dy > 12 && Math.abs(gesture.dy) > Math.abs(gesture.dx) * 1.5,
        onPanResponderRelease: (_event, gesture) => {
          if (gesture.dy > 90) onClose();
        },
      }),
    [onClose],
  );

  if (!story) return null;

  function goNext() {
    const result = next(player, stories.length);
    if (result.finished) onClose();
    else setPlayer(result.state);
  }

  function confirmDelete() {
    const target = story;
    if (!target) return;
    Alert.alert(t('mobile.feed.story.deleteTitle'), t('mobile.feed.story.deleteBody'), [
      { text: t('common.cancel'), style: 'cancel' },
      {
        text: t('mobile.feed.menu.delete'),
        style: 'destructive',
        onPress: () => {
          deleteStory(target.schoolId, target.id)
            .then(() => {
              setRemoved((current) => new Set(current).add(target.id));
              void queryClient.invalidateQueries({ queryKey: [FEED_ROOT, 'stories'] });
            })
            .catch((error) => Alert.alert(problemOf(error)?.title ?? t('mobile.feed.actionFailed'), problemOf(error)?.detail ?? t('mobile.feed.actionFailedBody')));
        },
      },
    ]);
  }

  const label = authorLabel(story.author);
  const author = label.kind === 'name' ? label.name : t(`mobile.feed.author.${label.role}`);
  const left = remaining(story.expiresAt, clock);
  const leftText = left.unit === 'soon' ? t('mobile.feed.story.endsSoon') : left.unit === 'minutes' ? t('mobile.feed.story.minutesLeft', { value: left.value }) : t('mobile.feed.story.hoursLeft', { value: left.value });
  const paused = player.paused;

  return (
    <Modal visible animationType={reduceMotion ? 'none' : 'fade'} onRequestClose={onClose} supportedOrientations={['portrait']}>
      <View {...swipe.panHandlers} accessibilityViewIsModal style={{ flex: 1, backgroundColor: dark.background }}>
        <Pressable
          accessible={false}
          onPress={(event) => (tapZone(event.nativeEvent.locationX, width) === 'previous' ? setPlayer(previous(player)) : goNext())}
          onLongPress={() => {
            holding.current = true;
            setPlayer((state) => setPaused(state, true));
          }}
          delayLongPress={220}
          onPressOut={() => {
            if (!holding.current) return;
            holding.current = false;
            setPlayer((state) => setPaused(state, false));
          }}
          style={{ flex: 1, alignItems: 'center', justifyContent: 'center' }}>
          {story.media ? (
            <FeedImage
              key={story.id}
              mediaId={story.media.mediaId}
              available={story.media.variants}
              variant="MEDIUM"
              contentFit="contain"
              label={story.caption?.trim() || t('mobile.feed.story.photoLabel', { author })}
              style={{ width: '100%', height: '100%', backgroundColor: 'transparent' }}
            />
          ) : (
            <View style={{ paddingHorizontal: 32 }}>
              {story.mediaWithheld ? (
                <Text variant="label" style={{ color: dark.mutedForeground, textAlign: 'center', marginBottom: 12 }}>
                  {t('mobile.feed.withheld.title')}
                </Text>
              ) : null}
              <Text variant="title" style={{ color: dark.foreground, textAlign: 'center' }}>
                {story.caption}
              </Text>
            </View>
          )}
        </Pressable>

        <View pointerEvents="box-none" style={{ position: 'absolute', top: 0, left: 0, right: 0, paddingTop: insets.top + 8, paddingHorizontal: 12, gap: 10 }}>
          <View
            accessibilityRole="progressbar"
            accessibilityLabel={t('mobile.feed.story.progress', { n: player.index + 1, total: stories.length })}
            accessibilityValue={{ min: 0, max: stories.length, now: player.index + 1 }}
            style={{ flexDirection: 'row', gap: 4 }}>
            {stories.map((item, index) => (
              <View key={item.id} style={{ flex: 1, height: 3, borderRadius: 2, backgroundColor: dark.border, overflow: 'hidden' }}>
                <View style={{ width: `${Math.round((automatic || index !== player.index ? segmentFill(player, index, durationOf(item)) : 1) * 100)}%`, height: 3, backgroundColor: dark.foreground }} />
              </View>
            ))}
          </View>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <View style={{ flex: 1 }}>
              <Text variant="label" numberOfLines={1} style={{ color: dark.foreground }}>
                {author}
              </Text>
              <Text variant="caption" style={{ color: dark.mutedForeground }}>
                {leftText}
                {typeof story.seenCount === 'number' ? ` · ${t('mobile.feed.seenCount', { count: story.seenCount })}` : ''}
              </Text>
            </View>
            {story.canDelete ? <RoundButton label={t('mobile.feed.menu.delete')} ios="trash" android="delete" onPress={confirmDelete} /> : null}
            <RoundButton label={t('mobile.feed.close')} ios="xmark" android="close" onPress={onClose} />
          </View>
        </View>

        <View pointerEvents="box-none" style={{ position: 'absolute', bottom: 0, left: 0, right: 0, paddingBottom: insets.bottom + 12, paddingHorizontal: 16, gap: 10 }}>
          {story.media && story.caption ? (
            <View style={{ backgroundColor: dark.background, opacity: 0.85, padding: 12, borderRadius: 12 }} pointerEvents="none">
              <Text style={{ color: dark.foreground }}>{story.caption}</Text>
            </View>
          ) : null}
          {story.tags.length > 0 ? (
            <Text variant="caption" style={{ color: dark.mutedForeground }}>
              {t('mobile.feed.tagsLine', { names: story.tags.map((tag) => tag.displayName).join(', ') })}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', justifyContent: 'center', gap: 16 }}>
            <RoundButton label={t('mobile.feed.story.previous')} ios="chevron.left" android="chevron_left" onPress={() => setPlayer(previous(player))} />
            {automatic ? (
              <RoundButton
                label={paused ? t('mobile.feed.story.play') : t('mobile.feed.story.pause')}
                ios={paused ? 'play.fill' : 'pause.fill'}
                android={paused ? 'play_arrow' : 'pause'}
                onPress={() => setPlayer((state) => setPaused(state, !state.paused))}
              />
            ) : null}
            <RoundButton label={t('mobile.feed.story.next')} ios="chevron.right" android="chevron_right" onPress={goNext} />
          </View>
        </View>
      </View>
    </Modal>
  );
}

function RoundButton({ label, ios, android, onPress }: { label: string; ios: string; android: string; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      onPress={onPress}
      hitSlop={6}
      style={{ width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center', backgroundColor: dark.card }}>
      <SymbolView name={{ ios, android, web: android } as never} size={20} tintColor={dark.foreground} />
    </Pressable>
  );
}
