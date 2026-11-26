/** Timing of the story viewer: pure functions of elapsed time, so they are testable without a clock or a DOM. */

/** How long a story with a photo stays on screen before the next one. */
export const STORY_PHOTO_MS = 6_000;
/** A caption-only story is read, not looked at: a little longer. */
export const STORY_TEXT_MS = 8_000;

export function storyDuration(story: { media?: unknown }): number {
  return story.media ? STORY_PHOTO_MS : STORY_TEXT_MS;
}

/** 0 to 1: how much of the current story's bar is filled. */
export function progressOf(elapsedMs: number, durationMs: number): number {
  if (durationMs <= 0) return 1;
  return Math.min(1, Math.max(0, elapsedMs / durationMs));
}

export type Step = { done: false; index: number } | { done: true };

/** The story after `index`, or done at the end. */
export function stepForward(index: number, count: number): Step {
  return index + 1 < count ? { done: false, index: index + 1 } : { done: true };
}

/** The story before `index`; the first one stays (going back restarts it). */
export function stepBack(index: number): number {
  return Math.max(0, index - 1);
}

/** Where to start: the first story the viewer has not seen yet, else the first. */
export function startIndex(stories: readonly { viewedByMe?: boolean }[]): number {
  const index = stories.findIndex((s) => !s.viewedByMe);
  return index < 0 ? 0 : index;
}

export interface TimeLeft {
  expired: boolean;
  hours: number;
  minutes: number;
}

/** Time until a story expires (`expiresAt` is the API's instant; stories live 24 hours). */
export function timeLeft(expiresAt: string, now: number): TimeLeft {
  const end = Date.parse(expiresAt);
  const left = Number.isNaN(end) ? 0 : end - now;
  if (left <= 0) return { expired: true, hours: 0, minutes: 0 };
  const totalMinutes = Math.ceil(left / 60_000);
  return { expired: false, hours: Math.floor(totalMinutes / 60), minutes: totalMinutes % 60 };
}

/** Stories oldest first, the order they are told in. */
export function inViewingOrder<T extends { publishedAt: string }>(stories: readonly T[]): T[] {
  return stories.slice().sort((a, b) => Date.parse(a.publishedAt) - Date.parse(b.publishedAt));
}
