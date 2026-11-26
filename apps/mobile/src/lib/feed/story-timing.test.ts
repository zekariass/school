import { describe, expect, it } from 'vitest';

import {
  RESTART_WINDOW_MS,
  STORY_PHOTO_MS,
  STORY_TEXT_MS,
  START,
  durationOf,
  firstUnseenIndex,
  isLive,
  liveStories,
  next,
  previous,
  remaining,
  segmentFill,
  setPaused,
  startAt,
  tapZone,
  tick,
} from './story-timing';

const NOW = Date.parse('2026-11-26T10:00:00Z');
const story = (expiresAt: string, status: 'PUBLISHED' | 'HIDDEN' | 'EXPIRED' | 'DELETED' = 'PUBLISHED') => ({ expiresAt, status });

describe('expiry', () => {
  it('lives until expiresAt, whether or not the expiry job has run', () => {
    expect(isLive(story('2026-11-26T10:00:01Z'), NOW)).toBe(true);
    expect(isLive(story('2026-11-26T10:00:00Z'), NOW)).toBe(false);
    expect(isLive(story('2026-11-26T09:00:00Z'), NOW)).toBe(false);
    expect(isLive(story('nonsense'), NOW)).toBe(false);
  });
  it('is only for published stories', () => {
    expect(isLive(story('2026-11-27T10:00:00Z', 'EXPIRED'), NOW)).toBe(false);
    expect(isLive(story('2026-11-27T10:00:00Z', 'HIDDEN'), NOW)).toBe(false);
  });
  it('filters a list', () => {
    expect(liveStories([story('2026-11-27T10:00:00Z'), story('2026-11-25T10:00:00Z')], NOW)).toHaveLength(1);
  });
  it('says how long is left, coarsely', () => {
    expect(remaining('2026-11-26T13:30:00Z', NOW)).toEqual({ unit: 'hours', value: 3 });
    expect(remaining('2026-11-26T10:45:00Z', NOW)).toEqual({ unit: 'minutes', value: 45 });
    expect(remaining('2026-11-26T10:00:30Z', NOW)).toEqual({ unit: 'soon' });
    expect(remaining('x', NOW)).toEqual({ unit: 'soon' });
  });
});

describe('the player', () => {
  it('runs a photo for six seconds and a caption-only story for eight', () => {
    expect(durationOf({ media: { mediaId: 'm' } as never })).toBe(STORY_PHOTO_MS);
    expect(durationOf({})).toBe(STORY_TEXT_MS);
  });
  it('advances by itself when time is up', () => {
    let result = tick(START, 5000, 3, 6000);
    expect(result).toEqual({ state: { index: 0, elapsedMs: 5000, paused: false }, finished: false });
    result = tick(result.state, 1500, 3, 6000);
    expect(result).toEqual({ state: { index: 1, elapsedMs: 0, paused: false }, finished: false });
  });
  it('finishes after the last story', () => {
    const result = tick(startAt(2), 7000, 3, 6000);
    expect(result.finished).toBe(true);
  });
  it('holds while paused (press and hold)', () => {
    const paused = setPaused(START, true);
    expect(tick(paused, 10_000, 3, 6000)).toEqual({ state: paused, finished: false });
    expect(tick(setPaused(paused, false), 1000, 3, 6000).state.elapsedMs).toBe(1000);
    expect(setPaused(paused, true)).toBe(paused);
  });
  it('goes back to the previous story early, or replays a story that has played a while', () => {
    expect(previous({ index: 2, elapsedMs: RESTART_WINDOW_MS - 1, paused: false })).toEqual({ index: 1, elapsedMs: 0, paused: false });
    expect(previous({ index: 2, elapsedMs: RESTART_WINDOW_MS + 1, paused: false })).toEqual({ index: 2, elapsedMs: 0, paused: false });
    expect(previous({ index: 0, elapsedMs: 100, paused: false })).toEqual({ index: 0, elapsedMs: 0, paused: false });
  });
  it('next ends the viewer on the last story', () => {
    expect(next({ index: 0, elapsedMs: 100, paused: false }, 2)).toEqual({ state: { index: 1, elapsedMs: 0, paused: false }, finished: false });
    expect(next({ index: 1, elapsedMs: 100, paused: false }, 2).finished).toBe(true);
  });
  it('fills the progress bars: past full, current growing, future empty', () => {
    const state = { index: 1, elapsedMs: 3000, paused: false };
    expect(segmentFill(state, 0, 6000)).toBe(1);
    expect(segmentFill(state, 1, 6000)).toBe(0.5);
    expect(segmentFill(state, 2, 6000)).toBe(0);
    expect(segmentFill({ ...state, elapsedMs: 9000 }, 1, 6000)).toBe(1);
  });
  it('tells taps apart: left third goes back', () => {
    expect(tapZone(10, 300)).toBe('previous');
    expect(tapZone(100, 300)).toBe('next');
    expect(tapZone(10, 0)).toBe('next');
  });
  it('starts at the first story you have not seen', () => {
    expect(firstUnseenIndex([{ viewedByMe: true }, { viewedByMe: false }, { viewedByMe: false }])).toBe(1);
    expect(firstUnseenIndex([{ viewedByMe: true }])).toBe(0);
    expect(firstUnseenIndex([])).toBe(0);
  });
});
