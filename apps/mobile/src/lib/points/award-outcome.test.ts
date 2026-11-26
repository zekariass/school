import { describe, expect, it } from 'vitest';

import { canUndoAt, confirmationFromState } from './award-outcome';

describe('confirmationFromState', () => {
  it('reads a batch award', () => {
    expect(
      confirmationFromState({ batchId: 'b1', undoableUntil: '2026-01-01T00:00:10Z', transactions: [{ id: 't1' }, { id: 't2' }], replayed: false }),
    ).toEqual({ batchId: 'b1', undoableUntil: '2026-01-01T00:00:10Z', transactionIds: ['t1', 't2'] });
  });

  it('reads a single-student award without a batch id', () => {
    expect(confirmationFromState({ transactions: [{ id: 't' }] })).toEqual({ batchId: null, undoableUntil: null, transactionIds: ['t'] });
  });

  it('tolerates a state that is missing or unreadable (a replay after the retention job removed it)', () => {
    expect(confirmationFromState(null)).toEqual({ batchId: null, undoableUntil: null, transactionIds: [] });
    expect(confirmationFromState({ transactions: [null, { id: 3 }, { id: 'ok' }] })).toEqual({ batchId: null, undoableUntil: null, transactionIds: ['ok'] });
  });
});

describe('undo window', () => {
  it('allows undo only before undoableUntil', () => {
    const until = '2026-01-01T00:00:10Z';
    const t0 = Date.parse('2026-01-01T00:00:00Z');
    expect(canUndoAt(until, t0)).toBe(true);
    expect(canUndoAt(until, Date.parse(until))).toBe(false);
    expect(canUndoAt(null, t0)).toBe(false);
    expect(canUndoAt('not a date', t0)).toBe(false);
  });
});
