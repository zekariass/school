import { describe, expect, it } from 'vitest';

import { arcDash, donutModel, percentages } from './donut';

const cat = (categoryId: string, points: number, polarity: 'POSITIVE' | 'NEEDS_WORK' = 'POSITIVE') => ({
  categoryId,
  name: categoryId.toUpperCase(),
  points,
  polarity,
});

describe('donutModel', () => {
  it('splits positive points into slices that fill the circle, largest first', () => {
    const model = donutModel([cat('a', 2), cat('b', 6), cat('c', 4)]);
    expect(model.total).toBe(12);
    expect(model.slices.map((slice) => slice.categoryId)).toEqual(['b', 'c', 'a']);
    expect(model.slices[0]).toMatchObject({ startDegrees: 0, sweepDegrees: 180 });
    expect(model.slices.at(-1)!.startDegrees + model.slices.at(-1)!.sweepDegrees).toBeCloseTo(360);
  });

  it('keeps needs-work and empty categories out of the circle but lists needs-work', () => {
    const model = donutModel([cat('a', 3), cat('z', 0), cat('n', -2, 'NEEDS_WORK')]);
    expect(model.slices.map((slice) => slice.categoryId)).toEqual(['a']);
    expect(model.needsWork).toEqual([{ categoryId: 'n', name: 'N', points: -2 }]);
  });

  it('has no slices when nothing was earned', () => {
    expect(donutModel([]).slices).toEqual([]);
    expect(donutModel([cat('a', 0)]).total).toBe(0);
  });
});

describe('percentages', () => {
  it('adds up to exactly 100', () => {
    for (const parts of [[1, 1, 1], [1, 2, 4], [5, 5], [7]]) {
      const total = parts.reduce((a, b) => a + b, 0);
      const result = percentages(parts.map((part) => ({ share: part / total })));
      expect(result.reduce((a, b) => a + b, 0)).toBe(100);
    }
    expect(percentages([])).toEqual([]);
  });
});

describe('arcDash', () => {
  it('turns a share into an SVG stroke dash around the circle', () => {
    const dash = arcDash({ share: 0.25, startDegrees: 90 }, 50);
    const circumference = 2 * Math.PI * 50;
    expect(dash.circumference).toBeCloseTo(circumference);
    expect(dash.dashArray).toBe(`${0.25 * circumference} ${circumference - 0.25 * circumference}`);
    expect(dash.dashOffset).toBeCloseTo(-circumference / 4);
  });

  it('leaves a small gap between slices, none for a full circle', () => {
    const gapped = arcDash({ share: 0.5, startDegrees: 0 }, 40, 4);
    expect(Number(gapped.dashArray.split(' ')[0])).toBeLessThan(0.5 * 2 * Math.PI * 40);
    const full = arcDash({ share: 1, startDegrees: 0 }, 40, 4);
    expect(Number(full.dashArray.split(' ')[0])).toBeCloseTo(2 * Math.PI * 40);
  });
});
