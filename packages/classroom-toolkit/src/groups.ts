import { shuffle, type Rng } from "./random";

export type GroupSpec = { readonly groupCount: number } | { readonly groupSize: number };

/**
 * Splits `ids` into random groups whose sizes differ by at most one. `groupCount` is capped at the number of
 * students; `groupSize` means "about this many per group" (the count is ceil(n / size), then balanced).
 * Returns [] for no students or an invalid spec.
 */
export function makeGroups(ids: readonly string[], spec: GroupSpec, rng: Rng = Math.random): string[][] {
  const n = ids.length;
  if (n === 0) return [];
  let count: number;
  if ("groupCount" in spec) {
    count = Math.floor(spec.groupCount);
  } else {
    const size = Math.floor(spec.groupSize);
    count = size >= 1 ? Math.ceil(n / size) : 0;
  }
  if (!Number.isFinite(count) || count < 1) return [];
  count = Math.min(count, n);
  const shuffled = shuffle(ids, rng);
  const groups: string[][] = Array.from({ length: count }, () => []);
  shuffled.forEach((id, i) => groups[i % count]!.push(id));
  return groups;
}
