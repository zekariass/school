import type { StoryView } from './types';

/** How long a story stays on screen before it moves on by itself (a photo, and a caption-only story that needs reading time). */
export const STORY_PHOTO_MS = 6000;
export const STORY_TEXT_MS = 8000;
/** Going back within this time of a story's start jumps to the previous story; later, it replays the current one. */
export const RESTART_WINDOW_MS = 1500;

/** A story that is still live at `nowMs`. The API already stops serving expired ones, but a viewer left open may outlive one. */
export function isLive(story: Pick<StoryView, 'expiresAt' | 'status'>, nowMs: number): boolean {
  if (story.status !== 'PUBLISHED') return false;
  const expires = Date.parse(story.expiresAt);
  return Number.isFinite(expires) && expires > nowMs;
}

export function liveStories<T extends Pick<StoryView, 'expiresAt' | 'status'>>(stories: readonly T[], nowMs: number): T[] {
  return stories.filter((story) => isLive(story, nowMs));
}

export function durationOf(story: Pick<StoryView, 'media'>): number {
  return story.media ? STORY_PHOTO_MS : STORY_TEXT_MS;
}

export interface PlayerState {
  index: number;
  elapsedMs: number;
  paused: boolean;
}

export const START: PlayerState = { index: 0, elapsedMs: 0, paused: false };

export function startAt(index: number): PlayerState {
  return { index: Math.max(0, index), elapsedMs: 0, paused: false };
}

export type PlayerResult = { state: PlayerState; finished: false } | { state: PlayerState; finished: true };

/** Moves to the next story, or finishes after the last one. */
export function next(state: PlayerState, count: number): PlayerResult {
  if (state.index + 1 >= count) return { state: { ...state, elapsedMs: 0 }, finished: true };
  return { state: { index: state.index + 1, elapsedMs: 0, paused: false }, finished: false };
}

/** The previous story, or the start of this one when it is the first or has played a while. */
export function previous(state: PlayerState): PlayerState {
  if (state.index > 0 && state.elapsedMs < RESTART_WINDOW_MS) return { index: state.index - 1, elapsedMs: 0, paused: false };
  return { ...state, elapsedMs: 0 };
}

/** Time passes: nothing happens while paused; reaching the end moves on. `durationMs` is the current story's. */
export function tick(state: PlayerState, deltaMs: number, count: number, durationMs: number): PlayerResult {
  if (state.paused || deltaMs <= 0) return { state, finished: false };
  const elapsedMs = state.elapsedMs + deltaMs;
  if (elapsedMs >= durationMs) return next(state, count);
  return { state: { ...state, elapsedMs }, finished: false };
}

export function setPaused(state: PlayerState, paused: boolean): PlayerState {
  return state.paused === paused ? state : { ...state, paused };
}

/** Fill of the progress bar of segment `segment` (0..1): past ones full, the current one growing, later ones empty. */
export function segmentFill(state: PlayerState, segment: number, durationMs: number): number {
  if (segment < state.index) return 1;
  if (segment > state.index) return 0;
  return durationMs > 0 ? Math.min(1, state.elapsedMs / durationMs) : 0;
}

/** Which way a tap goes: the left third goes back, the rest goes on. */
export function tapZone(x: number, width: number): 'previous' | 'next' {
  return width > 0 && x < width * 0.3 ? 'previous' : 'next';
}

export type Remaining = { unit: 'hours'; value: number } | { unit: 'minutes'; value: number } | { unit: 'soon' };

/** How long a story still has to live, coarse: whole hours, then minutes, then "soon". */
export function remaining(expiresAt: string, nowMs: number): Remaining {
  const ms = Date.parse(expiresAt) - nowMs;
  if (!Number.isFinite(ms) || ms < 60_000) return { unit: 'soon' };
  if (ms < 3_600_000) return { unit: 'minutes', value: Math.floor(ms / 60_000) };
  return { unit: 'hours', value: Math.floor(ms / 3_600_000) };
}

/** The story to open first: the oldest unseen one of the strip, else the first. */
export function firstUnseenIndex(stories: readonly Pick<StoryView, 'viewedByMe'>[]): number {
  const index = stories.findIndex((story) => !story.viewedByMe);
  return index < 0 ? 0 : index;
}
