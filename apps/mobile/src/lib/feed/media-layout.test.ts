import { describe, expect, it } from 'vitest';

import { aspectOf, clampAspect, gridLayout, listVariant, pickVariant } from './media-layout';

describe('pickVariant', () => {
  it('gives what was asked for when the item has it', () => {
    expect(pickVariant(['THUMB', 'SMALL', 'MEDIUM'], 'SMALL')).toBe('SMALL');
  });
  it('takes the next smaller one first (saves data), then a larger', () => {
    expect(pickVariant(['THUMB', 'MEDIUM'], 'SMALL')).toBe('THUMB');
    expect(pickVariant(['MEDIUM'], 'THUMB')).toBe('MEDIUM');
  });
  it('never offers an original or a rendition the feed does not use', () => {
    expect(pickVariant(['LARGE', 'POSTER', 'HLS_MASTER'], 'SMALL')).toBeNull();
    expect(pickVariant(['LARGE', 'SMALL'], 'MEDIUM')).toBe('SMALL');
  });
});

describe('listVariant', () => {
  it('uses thumbnails for small cells and SMALL for big ones, never MEDIUM', () => {
    expect(listVariant(120)).toBe('THUMB');
    expect(listVariant(180)).toBe('SMALL');
    expect(listVariant(2000)).toBe('SMALL');
  });
});

describe('gridLayout', () => {
  const shape = (count: number) => gridLayout(count).rows.map((row) => row.cells.map((cell) => cell.index));
  it('tiles one to four photos', () => {
    expect(shape(0)).toEqual([]);
    expect(shape(1)).toEqual([[0]]);
    expect(shape(2)).toEqual([[0, 1]]);
    expect(shape(3)).toEqual([[0], [1, 2]]);
    expect(shape(4)).toEqual([[0, 1], [2, 3]]);
  });
  it('shows four and counts the rest', () => {
    expect(shape(10)).toEqual([[0, 1], [2, 3]]);
    expect(gridLayout(10).more).toBe(6);
    expect(gridLayout(4).more).toBe(0);
  });
  it('keeps a lone photo\'s shape within reason', () => {
    expect(gridLayout(1, 3).rows[0]?.aspect).toBe(1.8);
    expect(gridLayout(1, 0.3).rows[0]?.aspect).toBe(0.8);
    expect(clampAspect(Number.NaN)).toBeCloseTo(4 / 3);
  });
  it('reads the aspect of an item', () => {
    expect(aspectOf({ width: 800, height: 400 })).toBe(2);
    expect(aspectOf({})).toBeCloseTo(4 / 3);
  });
});
