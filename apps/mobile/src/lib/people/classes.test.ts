import { describe, expect, it } from 'vitest';

import { canEditRoster, classColor, classGlyph, moveTargets, teacherNames, type MyClassLike } from './classes';

const cls = (over: Partial<MyClassLike> & { id: string }): MyClassLike => ({
  schoolId: 'school-1',
  name: 'Grade 3A',
  status: 'ACTIVE',
  role: 'TEACHER',
  ...over,
});

describe('class badge', () => {
  it('uses the class colour when valid, else a stable colour', () => {
    expect(classColor({ id: 'c1', color: '#aabbcc' })).toBe('#aabbcc');
    expect(classColor({ id: 'c1', color: 'blue' })).toBe(classColor({ id: 'c1' }));
  });

  it('shows a short icon, else the first letter of the name', () => {
    expect(classGlyph({ name: 'Grade 3A', icon: '🦁' })).toBe('🦁');
    expect(classGlyph({ name: 'Grade 3A', icon: 'book.closed.fill' })).toBe('G');
    expect(classGlyph({ name: 'አማርኛ' })).toBe('አ');
    expect(classGlyph({ name: '  ' })).toBe('?');
  });
});

describe('moveTargets', () => {
  const current = cls({ id: 'c1' });
  const mine = [
    current,
    cls({ id: 'c2' }),
    cls({ id: 'c3', role: 'STUDENT' }),
    cls({ id: 'c4', status: 'ARCHIVED' }),
    cls({ id: 'c5', schoolId: 'school-2' }),
  ];

  it('offers only the other active classes the caller teaches at the same school', () => {
    expect(moveTargets(mine, current).map((c) => c.id)).toEqual(['c2']);
  });

  it('offers nothing when the teacher has no other class', () => {
    expect(moveTargets([current], current)).toEqual([]);
  });
});

describe('roster permissions in the UI', () => {
  it('lets teachers of an active class edit the roster', () => {
    expect(canEditRoster(cls({ id: 'c1' }))).toBe(true);
    expect(canEditRoster(cls({ id: 'c1', status: 'ARCHIVED' }))).toBe(false);
    expect(canEditRoster(cls({ id: 'c1', role: 'STUDENT' }))).toBe(false);
    expect(canEditRoster(undefined)).toBe(false);
  });
});

describe('teacherNames', () => {
  it('joins the names in the order given', () => {
    expect(teacherNames([{ displayName: 'Anna Bekele' }, { displayName: 'Dawit Hailu' }])).toBe('Anna Bekele, Dawit Hailu');
    expect(teacherNames([])).toBe('');
  });
});
