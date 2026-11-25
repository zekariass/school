import { describe, expect, it } from 'vitest';

import { AVATAR_PALETTE, avatarColor, hashString, initialsOf } from './avatar';

describe('avatarColor', () => {
  it('is deterministic and comes from the palette', () => {
    const id = '0198a1c2-7b1e-7c3a-9f2d-3a4b5c6d7e8f';
    expect(avatarColor(id)).toBe(avatarColor(id));
    expect(AVATAR_PALETTE).toContain(avatarColor(id) as (typeof AVATAR_PALETTE)[number]);
  });

  it('spreads different people over several colours', () => {
    const colors = new Set(Array.from({ length: 40 }, (_, i) => avatarColor(`student-${i}`)));
    expect(colors.size).toBeGreaterThan(4);
  });

  it('prefers a valid colour from the avatar configuration', () => {
    expect(avatarColor('x', { color: '#112233' })).toBe('#112233');
    expect(avatarColor('x', { color: 'red' })).toBe(avatarColor('x'));
    expect(avatarColor('x', null)).toBe(avatarColor('x'));
  });

  it('hashes the same string to the same number on every run', () => {
    expect(hashString('abc')).toBe(hashString('abc'));
    expect(hashString('abc')).not.toBe(hashString('abd'));
  });
});

describe('initialsOf', () => {
  it('takes the first letters of the first two names', () => {
    expect(initialsOf('Abebe Kebede Tadesse')).toBe('AK');
    expect(initialsOf('abebe')).toBe('A');
    expect(initialsOf('  Abebe   Kebede ')).toBe('AK');
    expect(initialsOf('Abebe Kebede', 1)).toBe('A');
  });

  it('works with Ethiopic names and empty input', () => {
    expect(initialsOf('አበበ ከበደ')).toBe('አከ');
    expect(initialsOf('')).toBe('');
  });
});
