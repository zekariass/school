/** The quick-undo window of awards: entries live in memory (no child data is stored), expire by the clock. */

/** What reversing an award calls: a multi-student award is undone as a batch, a single row by its id. */
export type ReverseTarget = { kind: "batch"; id: string } | { kind: "transaction"; id: string };

/**
 * How to undo an award from its response: a batch id when the API gave one (several students or the whole
 * class), else the single row. Null when there is nothing to reverse.
 */
export function reverseTarget(result: { batchId?: string; transactions: readonly { id: string }[] }): ReverseTarget | null {
  if (result.batchId) return { kind: "batch", id: result.batchId };
  const only = result.transactions.length === 1 ? result.transactions[0] : undefined;
  return only ? { kind: "transaction", id: only.id } : null;
}

export interface UndoEntry {
  /** Unique key of the entry (the id of its reverse target). */
  id: string;
  classId: string;
  target: ReverseTarget;
  studentIds: readonly string[];
  /** The signed amount the award added to each student's total. */
  delta: number;
  /** ISO instant until which the awarder may undo. */
  undoableUntil: string;
  /** The class's name, so the toast says where the award was when the teacher moved on. */
  className: string;
  /** Name of the skill and who got it, as shown in the toast. */
  label: string;
}

/** Whole seconds left (rounded up, never negative); 0 once the window closed. */
export function secondsLeft(undoableUntil: string, now: number): number {
  const until = Date.parse(undoableUntil);
  if (Number.isNaN(until)) return 0;
  return Math.max(0, Math.ceil((until - now) / 1000));
}

export const isUndoable = (entry: Pick<UndoEntry, "undoableUntil">, now: number): boolean =>
  secondsLeft(entry.undoableUntil, now) > 0;

export const pruneExpired = (entries: readonly UndoEntry[], now: number): UndoEntry[] =>
  entries.filter((e) => isUndoable(e, now));

const MAX_ENTRIES = 5;

/** Adds an entry (newest first, at most five) and drops the expired ones; a repeated id replaces its entry. */
export function addEntry(entries: readonly UndoEntry[], entry: UndoEntry, now: number): UndoEntry[] {
  if (!isUndoable(entry, now)) return pruneExpired(entries, now);
  return [entry, ...entries.filter((e) => e.id !== entry.id)].filter((e) => isUndoable(e, now)).slice(0, MAX_ENTRIES);
}

export const removeEntry = (entries: readonly UndoEntry[], id: string): UndoEntry[] =>
  entries.filter((e) => e.id !== id);

/** "0:42" style countdown for the toast. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
