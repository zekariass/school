import { describe, expect, it } from 'vitest';

import { canUndoAt, classifyFailure, confirmation, outcomeFromResult } from './award-outcome';

describe('classifyFailure', () => {
  it('reads "no answer" as offline', () => {
    expect(classifyFailure(undefined, undefined)).toEqual({ kind: 'retry', reason: 'network' });
  });

  it('keeps the award on 5xx', () => {
    for (const status of [500, 502, 503, 504]) {
      expect(classifyFailure(status, undefined)).toEqual({ kind: 'retry', reason: 'server' });
    }
  });

  it('retries "not now" statuses, with Retry-After', () => {
    expect(classifyFailure(429, undefined, 30)).toEqual({ kind: 'retry', reason: 'throttled', retryAfterMs: 30_000 });
    expect(classifyFailure(408, undefined)).toEqual({ kind: 'retry', reason: 'throttled' });
    expect(classifyFailure(401, undefined)).toEqual({ kind: 'retry', reason: 'server' });
  });

  it('treats other 4xx as final and passes the localized detail on', () => {
    expect(classifyFailure(403, { code: 'points.feature_disabled', detail: 'Points are off' })).toEqual({
      kind: 'rejected',
      status: 403,
      code: 'points.feature_disabled',
      detail: 'Points are off',
    });
    for (const status of [400, 404, 409, 422]) expect(classifyFailure(status, undefined).kind).toBe('rejected');
  });

  it('does not guess that an unexpected status went through', () => {
    expect(classifyFailure(302, undefined).kind).toBe('retry');
  });
});

describe('outcomeFromResult', () => {
  const body = { batchId: 'b', replayed: false, undoableUntil: '2026-01-01T00:00:00Z', transactions: [{ id: 't1' }, { id: 't2' }] };

  it('confirms a 201', () => {
    expect(outcomeFromResult({ data: body, response: { status: 201 } })).toEqual({
      kind: 'confirmed',
      replayed: false,
      confirmation: { batchId: 'b', undoableUntil: '2026-01-01T00:00:00Z', transactionIds: ['t1', 't2'] },
    });
  });

  it('confirms a 200 replay as a replay', () => {
    const outcome = outcomeFromResult({ data: { ...body, replayed: true }, response: { status: 200 } });
    expect(outcome).toMatchObject({ kind: 'confirmed', replayed: true });
  });

  it('classifies an error response with its problem and Retry-After', () => {
    const headers = { get: (name: string) => (name === 'Retry-After' ? '7' : null) };
    expect(outcomeFromResult({ error: { code: 'x' }, response: { status: 429, headers } })).toEqual({
      kind: 'retry',
      reason: 'throttled',
      retryAfterMs: 7000,
    });
    expect(outcomeFromResult({ error: { code: 'points.access_denied', detail: 'No' }, response: { status: 403 } })).toEqual({
      kind: 'rejected',
      status: 403,
      code: 'points.access_denied',
      detail: 'No',
    });
  });

  it('does not confirm a 2xx without a body', () => {
    expect(outcomeFromResult({ response: { status: 200 } }).kind).toBe('retry');
  });
});

describe('confirmation and undo window', () => {
  it('reads a single-student award without a batch id', () => {
    expect(confirmation({ transactions: [{ id: 't' }] })).toEqual({ batchId: null, undoableUntil: null, transactionIds: ['t'] });
  });

  it('allows undo only before undoableUntil', () => {
    const until = '2026-01-01T00:00:10Z';
    const t0 = Date.parse('2026-01-01T00:00:00Z');
    expect(canUndoAt(until, t0)).toBe(true);
    expect(canUndoAt(until, Date.parse(until))).toBe(false);
    expect(canUndoAt(null, t0)).toBe(false);
    expect(canUndoAt('not a date', t0)).toBe(false);
  });
});
