/**
 * One award is one user action with one `clientOpId`. When the call fails without an answer (the network
 * dropped), retrying the same action must send the same id, so a request that did arrive is not applied twice
 * (the API answers 200 `replayed` instead of 201). A new action, or the same one after it succeeded, gets a new id.
 */

export interface AwardIntent {
  categoryId: string;
  /** Sorted by the tracker; irrelevant when `wholeClass`. */
  studentIds: readonly string[];
  wholeClass: boolean;
  /** 1-10 override, or null for the category default. */
  points: number | null;
  note: string;
}

export interface AwardBody {
  categoryId: string;
  studentIds?: string[];
  wholeClass?: boolean;
  points?: number;
  note?: string;
  clientOpId: string;
}

export function fingerprint(intent: AwardIntent): string {
  return JSON.stringify([
    intent.categoryId,
    intent.wholeClass ? "all" : [...intent.studentIds].sort(),
    intent.points,
    intent.note.trim(),
  ]);
}

export function toBody(intent: AwardIntent, clientOpId: string): AwardBody {
  const note = intent.note.trim();
  return {
    categoryId: intent.categoryId,
    ...(intent.wholeClass ? { wholeClass: true } : { studentIds: [...intent.studentIds] }),
    ...(intent.points !== null ? { points: intent.points } : {}),
    ...(note ? { note } : {}),
    clientOpId,
  };
}

export interface OpTracker {
  /** The body to send for this intent: same `clientOpId` while the previous attempt of it did not succeed. */
  begin(intent: AwardIntent): AwardBody;
  /** The action went through (201 or a 200 replay): the next action starts a new operation. */
  succeeded(): void;
}

export function createOpTracker(newId: () => string): OpTracker {
  let pending: { print: string; id: string } | null = null;
  return {
    begin(intent) {
      const print = fingerprint(intent);
      if (!pending || pending.print !== print) pending = { print, id: newId() };
      return toBody(intent, pending.id);
    },
    succeeded() {
      pending = null;
    },
  };
}
