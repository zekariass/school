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

/** How a post's photos tile (the collage of the design). */
export type CollageShape = 'single' | 'pair' | 'trio';

export interface Collage {
  shape: CollageShape;
  /** Photos shown, as indexes into the post's media, in reading order (the first is the large one of a trio). */
  cells: number[];
  /** width / height of the whole collage. */
  aspect: number;
  /** Photos beyond the cells shown, drawn as "+N" on the last cell. */
  more: number;
}

/** Share of the width taken by the large photo and by the stacked pair of a trio (5 : 3, as in the design). */
export const TRIO_SPLIT = { main: 5, side: 3 } as const;

/** Photos shown at most: one large and two stacked. */
export const COLLAGE_MAX = 3;

/**
 * The collage of a post: one wide photo, two side by side, or (three or more) one large photo with two stacked beside it
 * and a "+N" on the last when there are more. `soloAspect` is the width/height of a lone photo (clamped so a panorama or a
 * poster stays reasonable).
 */
export function collageLayout(count: number, soloAspect = 4 / 3): Collage {
  if (count <= 0) return { shape: 'single', cells: [], aspect: 1, more: 0 };
  if (count === 1) return { shape: 'single', cells: [0], aspect: clampAspect(soloAspect), more: 0 };
  if (count === 2) return { shape: 'pair', cells: [0, 1], aspect: 2, more: 0 };
  return { shape: 'trio', cells: [0, 1, 2], aspect: 1.6, more: count - COLLAGE_MAX };
}

/** The width of each cell of a collage `width` wide with `gap` between cells: what `listVariant` needs to pick THUMB or SMALL. */
export function cellWidths(collage: Collage, width: number, gap: number): number[] {
  if (collage.cells.length === 0 || width <= 0) return [];
  if (collage.shape === 'single') return [width];
  if (collage.shape === 'pair') return [(width - gap) / 2, (width - gap) / 2];
  const room = width - gap;
  const total = TRIO_SPLIT.main + TRIO_SPLIT.side;
  const side = (room * TRIO_SPLIT.side) / total;
  return [(room * TRIO_SPLIT.main) / total, side, side];
}

export function clampAspect(aspect: number): number {
  if (!Number.isFinite(aspect) || aspect <= 0) return 4 / 3;
  return Math.min(1.8, Math.max(0.8, aspect));
}

export function aspectOf(item: Pick<FeedMediaItem, 'width' | 'height'>): number {
  return item.width && item.height ? item.width / item.height : 4 / 3;
}
