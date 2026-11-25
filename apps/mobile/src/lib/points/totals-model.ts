import { applyDelta, type Totals } from '@school/classroom-toolkit';

/**
 * The totals shown on the class grid. The API's class overview is eventually consistent (a projection fed by
 * events), so the screen shows `base` (the last overview) plus the awards it knows the overview may not contain
 * yet. An award stays in `pending` until a later overview is trusted to include it:
 * - `queued`: not on the server yet (offline or sending): always applied;
 * - `confirmed`: the server has it; dropped once an overview that was requested `settleMs` after the confirmation
 *   arrives (the projection had time to catch up), so a fast refetch does not make the bubble jump back.
 * Undoing a confirmed award adds a confirmed entry with the opposite delta (the server writes a reversal row);
 * undoing a queued one just removes it.
 */
export interface PendingAward {
  readonly opId: string;
  readonly studentIds: readonly string[];
  /** Signed: needs-work awards and reversals are negative. */
  readonly delta: number;
  readonly state: 'queued' | 'confirmed';
  /** Epoch ms of the confirmation (only when `confirmed`). */
  readonly confirmedAt: number | null;
}

export interface TotalsModel {
  /** Net points per student from the last overview; null while none arrived (offline start). */
  readonly base: Totals | null;
  readonly pending: readonly PendingAward[];
}

export const EMPTY_TOTALS_MODEL: TotalsModel = { base: null, pending: [] };

/** The time the projection is given to catch up with a confirmed award. */
export const SETTLE_MS = 5_000;

export function addPending(model: TotalsModel, award: PendingAward): TotalsModel {
  if (model.pending.some((entry) => entry.opId === award.opId)) return model;
  return { ...model, pending: [...model.pending, award] };
}

export function removePending(model: TotalsModel, opId: string): TotalsModel {
  return model.pending.some((entry) => entry.opId === opId)
    ? { ...model, pending: model.pending.filter((entry) => entry.opId !== opId) }
    : model;
}

/** The server confirmed (201 or a replay): from now on a fresh overview may replace it. */
export function markConfirmed(model: TotalsModel, opId: string, at: number): TotalsModel {
  return {
    ...model,
    pending: model.pending.map((entry) =>
      entry.opId === opId && entry.state === 'queued' ? { ...entry, state: 'confirmed', confirmedAt: at } : entry,
    ),
  };
}

/**
 * True when an overview requested at `requestedAt` certainly contains this award: it was confirmed at least
 * `settleMs` before the request started. Queued awards are never settled by a fetch.
 */
export function isSettled(entry: PendingAward, requestedAt: number, settleMs = SETTLE_MS): boolean {
  return entry.state === 'confirmed' && entry.confirmedAt !== null && entry.confirmedAt + settleMs <= requestedAt;
}

/** A new overview arrived (`requestedAt` is when its request started): it replaces the base and settles awards. */
export function replaceBase(model: TotalsModel, base: Totals, requestedAt: number, settleMs = SETTLE_MS): TotalsModel {
  return { base, pending: model.pending.filter((entry) => !isSettled(entry, requestedAt, settleMs)) };
}

/** What the grid shows. With no overview yet only the pending awards are known. */
export function displayedTotals(model: TotalsModel): Totals {
  return model.pending.reduce<Totals>((totals, entry) => applyDelta(totals, entry.studentIds, entry.delta), model.base ?? {});
}

/** The net points of the whole class as shown (sum over the students on the grid). */
export function classTotal(totals: Totals, studentIds: readonly string[]): number {
  return studentIds.reduce((sum, id) => sum + (totals[id] ?? 0), 0);
}

/** The pending entry for a queued award, from what the queue stores. */
export function pendingFromQueued(
  item: { opId: string; studentIds: readonly string[]; wholeClass: boolean; points: number | null },
  category: { polarity: 'POSITIVE' | 'NEEDS_WORK'; defaultPoints: number } | undefined,
  rosterIds: readonly string[],
): PendingAward | null {
  if (!category) return null;
  const magnitude = item.points ?? category.defaultPoints;
  const delta = category.polarity === 'NEEDS_WORK' ? -magnitude : magnitude;
  return {
    opId: item.opId,
    studentIds: item.wholeClass ? rosterIds : item.studentIds,
    delta,
    state: 'queued',
    confirmedAt: null,
  };
}
