import { describe, expect, it } from 'vitest';

import { categoryColor, defaultPointsOf, formatSigned, iconFor, isLightColor, signedPoints, sortedCategories } from './categories';

const c = (id: string, polarity: 'POSITIVE' | 'NEEDS_WORK', sortOrder: number, extra: object = {}) => ({
  id,
  name: id,
  polarity,
  sortOrder,
  ...extra,
});

describe('sortedCategories', () => {
  it('lists positive skills first, in the school order, and keeps needs-work apart', () => {
    const { positive, needsWork } = sortedCategories([
      c('b', 'POSITIVE', 20),
      c('x', 'NEEDS_WORK', 110),
      c('a', 'POSITIVE', 10),
    ]);
    expect(positive.map((category) => category.id)).toEqual(['a', 'b']);
    expect(needsWork.map((category) => category.id)).toEqual(['x']);
  });

  it('returns no needs-work group when the API sent none (school switched it off)', () => {
    expect(sortedCategories([c('a', 'POSITIVE', 1)]).needsWork).toEqual([]);
  });

  it('leaves out inactive skills', () => {
    expect(sortedCategories([c('a', 'POSITIVE', 1, { active: false }), c('b', 'POSITIVE', 2)]).positive.map((category) => category.id)).toEqual(['b']);
  });
});

describe('points', () => {
  it('signs by polarity and uses the default unless overridden', () => {
    expect(signedPoints({ polarity: 'POSITIVE', defaultPoints: 2 }, null)).toBe(2);
    expect(signedPoints({ polarity: 'POSITIVE', defaultPoints: 2 }, 5)).toBe(5);
    expect(signedPoints({ polarity: 'NEEDS_WORK', defaultPoints: 1 }, 3)).toBe(-3);
    expect(defaultPointsOf({ defaultPoints: undefined })).toBe(1);
    expect(defaultPointsOf({ defaultPoints: 0 })).toBe(1);
  });

  it('formats with a real minus sign', () => {
    expect(formatSigned(3)).toBe('+3');
    expect(formatSigned(-2)).toBe('−2');
    expect(formatSigned(0)).toBe('0');
  });
});

describe('colours and icons', () => {
  it('accepts only #rrggbb', () => {
    expect(categoryColor({ color: '#2E9E6B' })).toBe('#2E9E6B');
    expect(categoryColor({ color: 'red' })).toBeNull();
    expect(categoryColor({ color: undefined })).toBeNull();
  });

  it('picks dark text on light colours and light text on dark ones', () => {
    expect(isLightColor('#FFE08A')).toBe(true);
    expect(isLightColor('#1F2937')).toBe(false);
    expect(isLightColor('#9F1239')).toBe(false);
  });

  it('knows the backend icon keys and falls back for others', () => {
    expect(iconFor('heart')).toEqual({ ios: 'heart.fill', android: 'favorite' });
    expect(iconFor('something-new').ios).toBe('star.circle.fill');
    expect(iconFor(undefined).android).toBe('star');
  });
});
