/** The quick-undo window of awards: entries live in memory (no child data is stored), expire by the clock. */

export interface UndoEntry {
  /** Unique key of the entry (the batch id of the award). */
  id: string;
  classId: string;
  /** Reversing the whole batch undoes every row. */
  batchId: string;
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
