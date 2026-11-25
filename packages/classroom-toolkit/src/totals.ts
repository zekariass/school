/** A student's net points in a class as the clients show them. */
export type Totals = Readonly<Record<string, number>>;

/**
 * Optimistic update of the class grid: the API's projection is eventually consistent, so a client adds an award
 * (or its undo, with the negated `delta`) to the shown totals at once and replaces them when the next class
 * overview arrives. `delta` is signed (needs-work is negative).
 */
export function applyDelta(totals: Totals, studentIds: readonly string[], delta: number): Totals {
  const next: Record<string, number> = { ...totals };
  for (const id of studentIds) next[id] = (next[id] ?? 0) + delta;
  return next;
}
