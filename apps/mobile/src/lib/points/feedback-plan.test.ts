import { describe, expect, it } from 'vitest';

import { feedbackPlan, MUTE_STORAGE_KEY, parseMuted, serializeMuted } from './feedback-plan';

describe('feedbackPlan', () => {
  it('plays a sound, a haptic and the animation by default', () => {
    expect(feedbackPlan('award', { muted: false, reduceMotion: false })).toEqual({ sound: 'award', haptic: 'success', animate: true });
  });

  it('has a gentler sound and haptic for needs-work and its own for the whole class', () => {
    expect(feedbackPlan('needsWork', { muted: false, reduceMotion: false })).toMatchObject({ sound: 'needs-work', haptic: 'warning' });
    expect(feedbackPlan('wholeClass', { muted: false, reduceMotion: false }).sound).toBe('whole-class');
  });

  it('mutes the sound only: the haptic tap stays', () => {
    expect(feedbackPlan('award', { muted: true, reduceMotion: false })).toEqual({ sound: null, haptic: 'success', animate: true });
  });

  it('skips the animation with reduce motion, sound and haptic unchanged', () => {
    expect(feedbackPlan('award', { muted: false, reduceMotion: true })).toEqual({ sound: 'award', haptic: 'success', animate: false });
  });

  it('covers every kind', () => {
    for (const kind of ['award', 'needsWork', 'wholeClass', 'undo', 'timerDone', 'pick'] as const) {
      expect(feedbackPlan(kind, { muted: false, reduceMotion: false }).sound).not.toBeNull();
    }
  });
});

describe('mute preference', () => {
  it('round-trips through the plain flag, and anything odd reads as not muted', () => {
    expect(parseMuted(serializeMuted(true))).toBe(true);
    expect(parseMuted(serializeMuted(false))).toBe(false);
    for (const value of [null, undefined, '', 'true', '2']) expect(parseMuted(value)).toBe(false);
  });

  it('uses a non-sensitive key', () => {
    expect(MUTE_STORAGE_KEY).toBe('school.points.muted');
  });
});
