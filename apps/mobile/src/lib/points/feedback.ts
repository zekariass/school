import { createAudioPlayer, setAudioModeAsync, type AudioPlayer } from 'expo-audio';
import * as Haptics from 'expo-haptics';
import { useSyncExternalStore } from 'react';

import { kv, saveQuietly } from '@/lib/storage';

import { feedbackPlan, MUTE_STORAGE_KEY, parseMuted, serializeMuted, type FeedbackKind, type SoundId } from './feedback-plan';

// Small original sounds made by `scripts/generate-sounds.mjs`.
const SOURCES: Record<SoundId, number> = {
  award: require('../../../assets/sounds/award.wav'),
  'whole-class': require('../../../assets/sounds/whole-class.wav'),
  'needs-work': require('../../../assets/sounds/needs-work.wav'),
  undo: require('../../../assets/sounds/undo.wav'),
  'timer-done': require('../../../assets/sounds/timer-done.wav'),
  pick: require('../../../assets/sounds/pick.wav'),
};

const players = new Map<SoundId, AudioPlayer>();
let modeReady: Promise<void> | null = null;

function playerFor(id: SoundId): AudioPlayer {
  let player = players.get(id);
  if (!player) {
    player = createAudioPlayer(SOURCES[id]);
    players.set(id, player);
  }
  return player;
}

function prepareAudio(): Promise<void> {
  // Sound effects mix with whatever else plays and follow the silent switch.
  modeReady ??= setAudioModeAsync({ playsInSilentMode: false, interruptionMode: 'mixWithOthers' }).catch(() => {});
  return modeReady;
}

async function playSound(id: SoundId): Promise<void> {
  await prepareAudio();
  const player = playerFor(id);
  await player.seekTo(0);
  player.play();
}

function playHaptic(id: 'success' | 'warning' | 'light'): void {
  const call =
    id === 'success'
      ? Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success)
      : id === 'warning'
        ? Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning)
        : Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  call.catch(() => {});
}

// The mute choice: a plain, non-sensitive flag in the key-value store.
let muted = false;
const muteListeners = new Set<() => void>();
void kv
  .getItem(MUTE_STORAGE_KEY)
  .then((value) => {
    muted = parseMuted(value);
    for (const listener of muteListeners) listener();
  })
  .catch(() => {});

export function setMuted(next: boolean): void {
  muted = next;
  saveQuietly(MUTE_STORAGE_KEY, serializeMuted(next));
  for (const listener of muteListeners) listener();
}

export function useMuted(): boolean {
  return useSyncExternalStore(
    (listener) => {
      muteListeners.add(listener);
      return () => muteListeners.delete(listener);
    },
    () => muted,
    () => muted,
  );
}

/** Plays the sound (unless muted) and the haptic tap of a feedback kind; returns whether to animate. */
export function playFeedback(kind: FeedbackKind, reduceMotion: boolean): boolean {
  const plan = feedbackPlan(kind, { muted, reduceMotion });
  if (plan.sound) void playSound(plan.sound).catch(() => {});
  if (plan.haptic !== 'none') playHaptic(plan.haptic);
  return plan.animate;
}
