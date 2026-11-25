import { type Rng } from "./random";

/** Who was already picked in this round. The round restarts when everybody has been picked. */
export interface PickerState {
  readonly picked: readonly string[];
}

export const EMPTY_PICKER: PickerState = { picked: [] };

export interface PickResult {
  /** The chosen id, or null when `ids` is empty. */
  readonly id: string | null;
  readonly state: PickerState;
  /** True when this pick started a new round (everybody had been picked, or the roster changed). */
  readonly newRound: boolean;
}

/**
 * Picks a student at random without repeating anyone until all `ids` were picked, then starts a new round
 * (never the same student twice in a row across rounds when there is more than one). Ids that left the roster
 * are forgotten; ids that joined are eligible at once.
 */
export function pickNext(ids: readonly string[], state: PickerState, rng: Rng = Math.random): PickResult {
  if (ids.length === 0) return { id: null, state: EMPTY_PICKER, newRound: false };
  const present = new Set(ids);
  let picked = state.picked.filter((id) => present.has(id));
  let pool = ids.filter((id) => !picked.includes(id));
  let newRound = false;
  if (pool.length === 0) {
    const last = picked[picked.length - 1];
    picked = [];
    pool = ids.length > 1 && last ? ids.filter((id) => id !== last) : ids.slice();
    newRound = true;
  }
  const id = pool[Math.floor(rng() * pool.length)]!;
  return { id, state: { picked: [...picked, id] }, newRound };
}
