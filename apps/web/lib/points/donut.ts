/** Donut chart of a student's points by category. Positive points only (needs-work is in the table). */

export interface CategoryTotalLike {
  categoryId: string;
  name: string;
  color?: string | null;
  points?: number;
  polarity: "POSITIVE" | "NEEDS_WORK";
}

export interface DonutSlice {
  categoryId: string;
  name: string;
  color: string;
  points: number;
  /** 0..1 */
  share: number;
  /** Start of the slice in 0..1 of the circle. */
  start: number;
}

const FALLBACK = "#2F80ED";
const HEX = /^#[0-9a-fA-F]{6}$/;

export function donutSlices(categories: readonly CategoryTotalLike[]): DonutSlice[] {
  const positive = categories
    .filter((c) => c.polarity === "POSITIVE" && (c.points ?? 0) > 0)
    .sort((a, b) => (b.points ?? 0) - (a.points ?? 0) || a.name.localeCompare(b.name));
  const sum = positive.reduce((n, c) => n + (c.points ?? 0), 0);
  if (sum === 0) return [];
  let start = 0;
  return positive.map((c) => {
    const points = c.points ?? 0;
    const share = points / sum;
    const slice: DonutSlice = {
      categoryId: c.categoryId,
      name: c.name,
      color: c.color && HEX.test(c.color) ? c.color : FALLBACK,
      points,
      share,
      start,
    };
    start += share;
    return slice;
  });
}

/** `stroke-dasharray` and `stroke-dashoffset` of a slice on a circle of circumference `c` (the arc starts at 12 o'clock). */
export function arcDash(slice: Pick<DonutSlice, "share" | "start">, c: number): { dasharray: string; dashoffset: number } {
  const length = Math.max(0, slice.share * c - 1.5); // a hairline gap between slices
  return { dasharray: `${length} ${c - length}`, dashoffset: -slice.start * c };
}
