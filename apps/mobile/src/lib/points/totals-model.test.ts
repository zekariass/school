import { describe, expect, it } from 'vitest';

import {
  addPending,
  classTotal,
  displayedTotals,
  EMPTY_TOTALS_MODEL,
  markConfirmed,
  pendingFromQueued,
  removePending,
  replaceBase,
  SETTLE_MS,
  type TotalsModel,
} from './totals-model';

const queued = (opId: string, studentIds: string[], delta: number) =>
  ({ opId, studentIds, delta, state: 'queued', confirmedAt: null }) as const;

describe('optimistic totals', () => {
  it('adds an award on top of the overview and shows it at once', () => {
    let model: TotalsModel = replaceBase(EMPTY_TOTALS_MODEL, { a: 5, b: 0 }, 0);
    model = addPending(model, queued('op1', ['a', 'b'], 2));
    expect(displayedTotals(model)).toEqual({ a: 7, b: 2 });
  });

  it('works before any overview arrived (offline start)', () => {
    const model = addPending(EMPTY_TOTALS_MODEL, queued('op1', ['a'], 1));
    expect(model.base).toBeNull();
    expect(displayedTotals(model)).toEqual({ a: 1 });
  });

  it('applies needs-work and several awards in order', () => {
    let model = replaceBase(EMPTY_TOTALS_MODEL, { a: 3 }, 0);
    model = addPending(model, queued('op1', ['a'], 2));
    model = addPending(model, queued('op2', ['a'], -1));
    expect(displayedTotals(model).a).toBe(4);
  });

  it('ignores the same op twice', () => {
    let model = replaceBase(EMPTY_TOTALS_MODEL, { a: 0 }, 0);
    model = addPending(model, queued('op1', ['a'], 2));
    model = addPending(model, queued('op1', ['a'], 2));
    expect(displayedTotals(model).a).toBe(2);
  });

  it('takes a cancelled award back out', () => {
    let model = replaceBase(EMPTY_TOTALS_MODEL, { a: 0 }, 0);
    model = addPending(model, queued('op1', ['a'], 2));
    model = removePending(model, 'op1');
    expect(displayedTotals(model).a).toBe(0);
  });
});

describe('settling with the next overview', () => {
  it('keeps a confirmed award until an overview requested after it had time to catch up', () => {
    let model = replaceBase(EMPTY_TOTALS_MODEL, { a: 5 }, 0);
    model = addPending(model, queued('op1', ['a'], 2));
    model = markConfirmed(model, 'op1', 10_000);

    // An overview requested right after the confirmation may not contain it yet: the bubble must not jump back.
    model = replaceBase(model, { a: 5 }, 10_500);
    expect(displayedTotals(model).a).toBe(7);

    // One requested after the projection had time to settle replaces it.
    model = replaceBase(model, { a: 7 }, 10_000 + SETTLE_MS);
    expect(model.pending).toEqual([]);
    expect(displayedTotals(model).a).toBe(7);
  });

  it('never drops an award that is still waiting to be sent', () => {
    let model = addPending(replaceBase(EMPTY_TOTALS_MODEL, { a: 5 }, 0), queued('op1', ['a'], 1));
    model = replaceBase(model, { a: 9 }, 999_999);
    expect(displayedTotals(model).a).toBe(10);
  });

  it('shows an undo of a confirmed award at once and settles it with the original', () => {
    let model = replaceBase(EMPTY_TOTALS_MODEL, { a: 5 }, 0);
    model = markConfirmed(addPending(model, queued('op1', ['a'], 2)), 'op1', 1_000);
    model = addPending(model, { opId: 'undo-op1', studentIds: ['a'], delta: -2, state: 'confirmed', confirmedAt: 2_000 });
    expect(displayedTotals(model).a).toBe(5);
    model = replaceBase(model, { a: 5 }, 2_000 + SETTLE_MS);
    expect(model.pending).toEqual([]);
    expect(displayedTotals(model).a).toBe(5);
  });

  it('markConfirmed only changes queued awards', () => {
    const model = markConfirmed(addPending(EMPTY_TOTALS_MODEL, queued('op1', ['a'], 1)), 'op1', 5);
    expect(markConfirmed(model, 'op1', 99).pending[0]!.confirmedAt).toBe(5);
  });
});

describe('helpers', () => {
  it('sums the class as shown', () => {
    expect(classTotal({ a: 2, b: -1, c: 4 }, ['a', 'b', 'd'])).toBe(1);
  });

  it('rebuilds a pending award from the queue (after an app restart)', () => {
    const skill = { polarity: 'NEEDS_WORK', defaultPoints: 2 } as const;
    expect(pendingFromQueued({ opId: 'o', studentIds: ['a'], wholeClass: false, points: null }, skill, ['a', 'b'])).toEqual(
      queued('o', ['a'], -2),
    );
    expect(pendingFromQueued({ opId: 'o', studentIds: [], wholeClass: true, points: 3 }, { polarity: 'POSITIVE', defaultPoints: 1 }, ['a', 'b'])).toEqual(
      queued('o', ['a', 'b'], 3),
    );
    expect(pendingFromQueued({ opId: 'o', studentIds: ['a'], wholeClass: false, points: 1 }, undefined, [])).toBeNull();
  });
});
