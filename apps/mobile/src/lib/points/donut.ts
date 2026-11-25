/** The donut chart of a child's points by category, and its text alternative. */

export interface CategoryTotalLike {
  categoryId: string;
  name: string;
  color?: string | undefined;
  points?: number | undefined;
  polarity: 'POSITIVE' | 'NEEDS_WORK';
}

export interface DonutSlice {
  categoryId: string;
  name: string;
  color: string | null;
  points: number;
  /** 0-1 of the positive total. */
  share: number;
  /** Start and sweep in degrees, clockwise from 12 o'clock. */
  startDegrees: number;
  sweepDegrees: number;
}

export interface DonutModel {
  slices: DonutSlice[];
  /** Sum of the slices (positive points only). */
  total: number;
  /** Needs-work categories are not slices (a donut shows what was earned); listed separately. */
  needsWork: { categoryId: string; name: string; points: number }[];
}

/**
 * Slices for the categories with positive net points, largest first (same order for the legend). A donut can
 * only show parts of a whole, so negative categories (needs-work) are returned apart and shown as a list.
 */
export function donutModel(categories: readonly CategoryTotalLike[]): DonutModel {
  const positive = categories
    .filter((category) => category.polarity === 'POSITIVE' && (category.points ?? 0) > 0)
    .sort((a, b) => (b.points ?? 0) - (a.points ?? 0) || a.name.localeCompare(b.name));
  const total = positive.reduce((sum, category) => sum + (category.points ?? 0), 0);
  let start = 0;
  const slices = positive.map((category) => {
    const points = category.points ?? 0;
    const share = total === 0 ? 0 : points / total;
    const sweep = share * 360;
    const slice: DonutSlice = {
      categoryId: category.categoryId,
      name: category.name,
      color: category.color ?? null,
      points,
      share,
      startDegrees: start,
      sweepDegrees: sweep,
    };
    start += sweep;
    return slice;
  });
  const needsWork = categories
    .filter((category) => category.polarity === 'NEEDS_WORK' && (category.points ?? 0) !== 0)
    .map((category) => ({ categoryId: category.categoryId, name: category.name, points: category.points ?? 0 }));
  return { slices, total, needsWork };
}

/** Stroke dash for one slice of a circle with the given radius (SVG `strokeDasharray` and `strokeDashoffset`). */
export function arcDash(slice: Pick<DonutSlice, 'share' | 'startDegrees'>, radius: number, gapDegrees = 0) {
  const circumference = 2 * Math.PI * radius;
  const gap = slice.share >= 0.999 ? 0 : (gapDegrees / 360) * circumference;
  const length = Math.max(0, slice.share * circumference - gap);
  return {
    circumference,
    dashArray: `${length} ${circumference - length}`,
    dashOffset: -((slice.startDegrees / 360) * circumference),
  };
}

/** Whole percent per slice that adds up to 100 (largest remainder), for the legend and the text alternative. */
export function percentages(slices: readonly Pick<DonutSlice, 'share'>[]): number[] {
  const raw = slices.map((slice) => slice.share * 100);
  const floors = raw.map(Math.floor);
  let left = slices.length === 0 ? 0 : 100 - floors.reduce((a, b) => a + b, 0);
  const order = raw
    .map((value, index) => ({ index, remainder: value - Math.floor(value) }))
    .sort((a, b) => b.remainder - a.remainder || a.index - b.index);
  for (const { index } of order) {
    if (left <= 0) break;
    floors[index] = (floors[index] ?? 0) + 1;
    left--;
  }
  return floors;
}
