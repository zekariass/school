/** Point categories ("skills"): which ones the award screen shows, in which order, and the fixed look choices. */

export interface CategoryLike {
  id: string;
  name: string;
  icon?: string | null;
  color?: string | null;
  defaultPoints?: number;
  polarity: "POSITIVE" | "NEEDS_WORK";
  context: "CLASS" | "SCHOOL" | "HOME";
  active?: boolean;
  sortOrder?: number;
  classId?: string | null;
}

/** Friendly colors for the tile (stored as `#rrggbb`); a fixed set so every choice stays readable. */
export const CATEGORY_COLORS = [
  "#27AE60",
  "#2F80ED",
  "#9B51E0",
  "#F2994A",
  "#E056A0",
  "#2D9CDB",
  "#EB5757",
  "#8D6E63",
] as const;

export const DEFAULT_CATEGORY_ICON = "star" as const;
export const DEFAULT_CATEGORY_COLOR = CATEGORY_COLORS[0];

const HEX = /^#[0-9a-fA-F]{6}$/;

export const categoryColor = (c: Pick<CategoryLike, "color">): string =>
  c.color && HEX.test(c.color) ? c.color : DEFAULT_CATEGORY_COLOR;

/** Readable text color (black or white) on a `#rrggbb` background, by relative luminance. */
export function readableOn(background: string): "#000000" | "#ffffff" {
  const hex = HEX.test(background) ? background : DEFAULT_CATEGORY_COLOR;
  const channel = (i: number) => {
    const v = parseInt(hex.slice(1 + i * 2, 3 + i * 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const luminance = 0.2126 * channel(0) + 0.7152 * channel(1) + 0.0722 * channel(2);
  return luminance > 0.4 ? "#000000" : "#ffffff";
}

export function compareCategories(a: CategoryLike, b: CategoryLike): number {
  if (a.polarity !== b.polarity) return a.polarity === "POSITIVE" ? -1 : 1;
  if ((a.sortOrder ?? 0) !== (b.sortOrder ?? 0)) return (a.sortOrder ?? 0) - (b.sortOrder ?? 0);
  return a.name.localeCompare(b.name);
}

/**
 * The tiles of the award screen: active CLASS categories only (HOME belongs to guardians), needs-work ones only
 * while the school allows them, positive first, then by `sortOrder` and name. The API already filters, this is
 * the second line (and keeps the screen right while the settings change).
 */
export function awardableCategories<T extends CategoryLike>(categories: readonly T[], needsWorkEnabled: boolean): T[] {
  return categories
    .filter((c) => c.active !== false && c.context !== "HOME" && (needsWorkEnabled || c.polarity === "POSITIVE"))
    .sort(compareCategories);
}

/** The categories of the management lists: active first, then the usual order. */
export function manageableCategories<T extends CategoryLike>(categories: readonly T[]): T[] {
  return categories.slice().sort((a, b) => Number(b.active !== false) - Number(a.active !== false) || compareCategories(a, b));
}

/** The points an award adds to a total: negative for needs-work. */
export function signedPoints(polarity: CategoryLike["polarity"], points: number): number {
  const magnitude = Math.abs(points);
  return polarity === "NEEDS_WORK" ? -magnitude : magnitude;
}

/** The points a tile awards: the chosen override (1-10) or the category's default. */
export function pointsFor(category: Pick<CategoryLike, "defaultPoints">, override: number | null): number {
  const value = override ?? category.defaultPoints ?? 1;
  return Math.min(10, Math.max(1, Math.round(value)));
}

/** Whether a category belongs to this class only (the class's own) rather than to the school's palette. */
export const isClassOwn = (c: Pick<CategoryLike, "classId">, classId: string): boolean => c.classId === classId;
