import type { FeedMediaItem } from './types';

export type Variant = 'THUMB' | 'SMALL' | 'MEDIUM';

const ORDER: readonly Variant[] = ['THUMB', 'SMALL', 'MEDIUM'];

/**
 * The variant to ask for: `wanted`, or the nearest one the item has (the lists never ask for MEDIUM, only the full-screen
 * viewer does; an original is never asked for and the API refuses it anyway). `null` when the item has none of the three.
 */
export function pickVariant(available: readonly string[], wanted: Variant): Variant | null {
  const have = ORDER.filter((variant) => available.includes(variant));
  if (have.length === 0) return null;
  if (have.includes(wanted)) return wanted;
  const wantedIndex = ORDER.indexOf(wanted);
  // Prefer the next smaller one (saves data), then the next larger.
  const smaller = [...have].reverse().find((variant) => ORDER.indexOf(variant) < wantedIndex);
  return smaller ?? have[0]!;
}

/** Variant for a list cell of `cellWidth` dp: thumbnails for small cells, SMALL for the big ones. Never MEDIUM. */
export function listVariant(cellWidth: number): Variant {
  return cellWidth <= 140 ? 'THUMB' : 'SMALL';
}

export interface GridCell {
  /** Index into the post's media. */
  index: number;
  /** Width of the cell as a share of the row (0..1). */
  flex: number;
}

export interface GridLayout {
  rows: { cells: GridCell[]; /** width / height */ aspect: number }[];
  /** Photos beyond the cells shown ("+3"). */
  more: number;
}

/**
 * How a post's photos tile: one wide photo, two side by side, one wide and two below, or 2 x 2 with a "+N" on the last cell.
 * `soloAspect` is the width/height of a lone photo (clamped so a panorama or a poster stays reasonable).
 */
export function gridLayout(count: number, soloAspect = 4 / 3): GridLayout {
  if (count <= 0) return { rows: [], more: 0 };
  if (count === 1) return { rows: [{ cells: [{ index: 0, flex: 1 }], aspect: clampAspect(soloAspect) }], more: 0 };
  if (count === 2) return { rows: [{ cells: [{ index: 0, flex: 1 }, { index: 1, flex: 1 }], aspect: 2 }], more: 0 };
  if (count === 3) {
    return {
      rows: [
        { cells: [{ index: 0, flex: 1 }], aspect: 16 / 9 },
        { cells: [{ index: 1, flex: 1 }, { index: 2, flex: 1 }], aspect: 2 },
      ],
      more: 0,
    };
  }
  return {
    rows: [
      { cells: [{ index: 0, flex: 1 }, { index: 1, flex: 1 }], aspect: 2 },
      { cells: [{ index: 2, flex: 1 }, { index: 3, flex: 1 }], aspect: 2 },
    ],
    more: count - 4,
  };
}

export function clampAspect(aspect: number): number {
  if (!Number.isFinite(aspect) || aspect <= 0) return 4 / 3;
  return Math.min(1.8, Math.max(0.8, aspect));
}

export function aspectOf(item: Pick<FeedMediaItem, 'width' | 'height'>): number {
  return item.width && item.height ? item.width / item.height : 4 / 3;
}
