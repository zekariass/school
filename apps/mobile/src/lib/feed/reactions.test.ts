import { describe, expect, it } from 'vitest';

import { applyReaction, nextReaction, totalReactions, withSummary } from './reactions';

const post = (counts: Record<string, number>, mine?: 'LIKE' | 'HEART' | 'CLAP' | 'STAR') => ({
  reactionCounts: counts,
  ...(mine ? { myReaction: mine } : {}),
});

describe('applyReaction', () => {
  it('adds a first reaction', () => {
    const after = applyReaction(post({}), 'HEART');
    expect(after.reactionCounts).toEqual({ HEART: 1 });
    expect(after.myReaction).toBe('HEART');
  });
  it('moves the reaction: one per person', () => {
    const after = applyReaction(post({ HEART: 1, LIKE: 2 }, 'HEART'), 'LIKE');
    expect(after.reactionCounts).toEqual({ LIKE: 3 });
    expect(after.myReaction).toBe('LIKE');
  });
  it('takes it back and drops empty counts', () => {
    const after = applyReaction(post({ STAR: 1 }, 'STAR'), null);
    expect(after.reactionCounts).toEqual({});
    expect('myReaction' in after).toBe(false);
  });
  it('changes nothing when it is already so, and never goes below zero', () => {
    const same = post({ CLAP: 1 }, 'CLAP');
    expect(applyReaction(same, 'CLAP')).toBe(same);
    expect(applyReaction(post({}, 'LIKE'), null).reactionCounts).toEqual({});
  });
  it('does not touch the input', () => {
    const before = post({ HEART: 1 }, 'HEART');
    applyReaction(before, 'STAR');
    expect(before.reactionCounts).toEqual({ HEART: 1 });
  });
});

describe('nextReaction', () => {
  it('toggles your own, replaces another', () => {
    expect(nextReaction('HEART', 'HEART')).toBeNull();
    expect(nextReaction('HEART', 'LIKE')).toBe('LIKE');
    expect(nextReaction(undefined, 'LIKE')).toBe('LIKE');
  });
});

describe('totals and summaries', () => {
  it('counts all reactions', () => {
    expect(totalReactions({ LIKE: 2, HEART: 3 })).toBe(5);
    expect(totalReactions(undefined)).toBe(0);
  });
  it('lets the server answer win', () => {
    const after = withSummary(post({ HEART: 9 }, 'HEART'), { counts: { LIKE: 1 } });
    expect(after.reactionCounts).toEqual({ LIKE: 1 });
    expect('myReaction' in after).toBe(false);
    expect(withSummary(post({}), { counts: { STAR: 1 }, mine: 'STAR' }).myReaction).toBe('STAR');
  });
});
