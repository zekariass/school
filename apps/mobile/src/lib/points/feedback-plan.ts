/**
 * What an award feels like. Sound is the teacher's choice (the mute toggle); haptics are not a sound, so they
 * stay on; the animation follows the OS "reduce motion" setting. The three are decided in one place so the
 * rules are testable.
 */

export type FeedbackKind = 'award' | 'needsWork' | 'wholeClass' | 'undo' | 'timerDone' | 'pick';

export type SoundId = 'award' | 'needs-work' | 'whole-class' | 'undo' | 'timer-done' | 'pick';
export type HapticId = 'success' | 'warning' | 'light' | 'none';

export interface FeedbackPlan {
  sound: SoundId | null;
  haptic: HapticId;
  /** A lightweight pop and a floating "+n"; false means a static highlight only. */
  animate: boolean;
}

const SOUND: Record<FeedbackKind, SoundId> = {
  award: 'award',
  needsWork: 'needs-work',
  wholeClass: 'whole-class',
  undo: 'undo',
  timerDone: 'timer-done',
  pick: 'pick',
};

const HAPTIC: Record<FeedbackKind, HapticId> = {
  award: 'success',
  needsWork: 'warning',
  wholeClass: 'success',
  undo: 'light',
  timerDone: 'success',
  pick: 'light',
};

export function feedbackPlan(kind: FeedbackKind, options: { muted: boolean; reduceMotion: boolean }): FeedbackPlan {
  return {
    sound: options.muted ? null : SOUND[kind],
    haptic: HAPTIC[kind],
    animate: !options.reduceMotion,
  };
}

/** The mute preference is a plain flag in the key-value store ("1" = muted). Anything else reads as not muted. */
export const MUTE_STORAGE_KEY = 'school.points.muted';

export function parseMuted(value: string | null | undefined): boolean {
  return value === '1';
}

export function serializeMuted(muted: boolean): string {
  return muted ? '1' : '0';
}
