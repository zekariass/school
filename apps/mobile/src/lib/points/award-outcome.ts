/** What the app needs from a confirmed award: the ids to undo it with, and until when that works. */
export interface AwardConfirmation {
  readonly batchId: string | null;
  readonly undoableUntil: string | null;
  readonly transactionIds: readonly string[];
}

const asRecord = (value: unknown): Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

/**
 * Reads the `state` of a POINT_AWARD result (the API's `AwardResult`): batch id (several students), transaction ids
 * and the undo window. A replayed operation returns the original rows, so the same reading applies.
 */
export function confirmationFromState(state: unknown): AwardConfirmation {
  const raw = asRecord(state);
  const transactions = Array.isArray(raw.transactions) ? raw.transactions : [];
  return {
    batchId: typeof raw.batchId === 'string' ? raw.batchId : null,
    undoableUntil: typeof raw.undoableUntil === 'string' ? raw.undoableUntil : null,
    transactionIds: transactions.flatMap((transaction) => {
      const id = asRecord(transaction).id;
      return typeof id === 'string' ? [id] : [];
    }),
  };
}

/** Whether the undo button may still be offered (the API has the last word: it answers 403 after the window). */
export function canUndoAt(undoableUntil: string | null | undefined, nowMs: number): boolean {
  if (!undoableUntil) return false;
  const until = Date.parse(undoableUntil);
  return Number.isFinite(until) && nowMs < until;
}
