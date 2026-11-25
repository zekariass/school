import { describe, expect, it } from 'vitest';

import { canSeePrivateFields, chunk, firstNameOf, gridLayout, toRoster, toRosterEntry } from './roster';

describe('roster view-model', () => {
  const row = {
    studentId: 's1',
    displayName: 'Abebe Kebede Tadesse',
    accessLevel: 'FULL' as const,
    avatar: { id: 'm1' },
    avatarConfig: { color: '#112233' },
    dateOfBirth: '2015-03-24',
    gender: 'MALE',
    username: 'abebe.k',
  };

  it('keeps name and avatar only, never the private fields', () => {
    const entry = toRosterEntry(row);
    expect(entry).toEqual({
      id: 's1',
      name: 'Abebe Kebede Tadesse',
      firstName: 'Abebe',
      avatarMediaId: 'm1',
      avatarConfig: { color: '#112233' },
      accessLevel: 'FULL',
    });
    expect(JSON.stringify(entry)).not.toContain('2015');
    expect(JSON.stringify(entry)).not.toContain('abebe.k');
  });

  it('handles rows without an avatar and Ethiopic names', () => {
    expect(toRoster([{ studentId: 's2', displayName: 'አበበ ከበደ', accessLevel: 'BASIC' }])).toEqual([
      { id: 's2', name: 'አበበ ከበደ', firstName: 'አበበ', avatarMediaId: null, avatarConfig: null, accessLevel: 'BASIC' },
    ]);
    expect(firstNameOf('  ')).toBe('');
  });

  it('shows private fields only at FULL access', () => {
    expect(canSeePrivateFields('FULL')).toBe(true);
    expect(canSeePrivateFields('BASIC')).toBe(false);
    expect(canSeePrivateFields(undefined)).toBe(false);
  });
});

describe('gridLayout', () => {
  it('uses 3 columns on a phone and 4 on a wide screen', () => {
    expect(gridLayout(350, { gap: 12 }).columns).toBe(3);
    expect(gridLayout(362, { gap: 12 }).columns).toBe(3); // iPhone 17 Pro content width
    expect(gridLayout(400, { gap: 12 }).columns).toBe(3);
    expect(gridLayout(700, { gap: 12 }).columns).toBe(4);
    expect(gridLayout(1200, { gap: 12 }).columns).toBe(4);
  });

  it('fills the width exactly with equal cells', () => {
    const { columns, cellWidth } = gridLayout(350, { gap: 12 });
    expect(columns * cellWidth + 12 * (columns - 1)).toBeLessThanOrEqual(350);
    expect(columns * cellWidth + 12 * (columns - 1)).toBeGreaterThan(350 - columns);
  });

  it('keeps avatars a sensible size and copes with no width yet', () => {
    expect(gridLayout(350, { gap: 12 }).avatarSize).toBeGreaterThanOrEqual(48);
    expect(gridLayout(1200, { gap: 12 }).avatarSize).toBeLessThanOrEqual(120);
    expect(gridLayout(0, { gap: 12 }).columns).toBe(3);
  });
});

describe('chunk', () => {
  it('splits into rows, the last one shorter', () => {
    expect(chunk([1, 2, 3, 4, 5, 6, 7], 3)).toEqual([[1, 2, 3], [4, 5, 6], [7]]);
    expect(chunk([], 3)).toEqual([]);
  });
});
