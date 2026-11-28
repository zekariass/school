import { describe, expect, it } from 'vitest';

import { aspectOf, cellWidths, clampAspect, collageLayout, listVariant, pickVariant } from './media-layout';

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

describe('collageLayout', () => {
  it('shows one wide photo, two side by side, or a large one with two beside it', () => {
    expect(collageLayout(0).cells).toEqual([]);
    expect(collageLayout(1)).toMatchObject({ shape: 'single', cells: [0], more: 0 });
    expect(collageLayout(2)).toMatchObject({ shape: 'pair', cells: [0, 1], more: 0 });
    expect(collageLayout(3)).toMatchObject({ shape: 'trio', cells: [0, 1, 2], more: 0 });
  });
  it('shows three at most and counts the rest for the "+N"', () => {
    expect(collageLayout(4)).toMatchObject({ shape: 'trio', cells: [0, 1, 2], more: 1 });
    expect(collageLayout(10).more).toBe(7);
  });
  it("keeps a lone photo's shape within reason", () => {
    expect(collageLayout(1, 3).aspect).toBe(1.8);
    expect(collageLayout(1, 0.3).aspect).toBe(0.8);
    expect(clampAspect(Number.NaN)).toBeCloseTo(4 / 3);
  });
  it('reads the aspect of an item', () => {
    expect(aspectOf({ width: 800, height: 400 })).toBe(2);
    expect(aspectOf({})).toBeCloseTo(4 / 3);
  });
});

describe('cellWidths', () => {
  it('gives the whole width to a lone photo and halves a pair', () => {
    expect(cellWidths(collageLayout(1), 300, 4)).toEqual([300]);
    expect(cellWidths(collageLayout(2), 304, 4)).toEqual([150, 150]);
  });
  it('splits a trio 5 : 3 and fills the width exactly', () => {
    const [main, a, b] = cellWidths(collageLayout(3), 304, 4) as [number, number, number];
    expect(main / a).toBeCloseTo(5 / 3);
    expect(a).toBe(b);
    expect(main + 4 + a).toBeCloseTo(304);
  });
  it('picks a thumbnail for a small cell and SMALL for a big one', () => {
    const widths = cellWidths(collageLayout(3), 340, 4);
    expect(listVariant(widths[0]!)).toBe('SMALL');
    expect(listVariant(widths[1]!)).toBe('THUMB');
  });
  it('has nothing to size without photos or width', () => {
    expect(cellWidths(collageLayout(0), 300, 4)).toEqual([]);
    expect(cellWidths(collageLayout(3), 0, 4)).toEqual([]);
  });
});
