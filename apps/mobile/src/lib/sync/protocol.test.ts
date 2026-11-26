import { describe, expect, it } from 'vitest';

import { classifyRequestFailure, readBatchResponse, readResult, toRequestOperation } from './protocol';
import { CATEGORY, CLASS, SCHOOL, conflictState, u } from './test-support';
import type { QueuedOperation } from './types';

const DEVICE = u(800);

const attendance: QueuedOperation = {
  type: 'ATTENDANCE_MARK',
  opId: u(1),
  ownerId: 'teacher-1',
  schoolId: SCHOOL,
  classId: CLASS,
  baseVersion: 4,
  createdAt: Date.UTC(2026, 10, 25, 7, 30),
  attempts: 0,
  nextAttemptAt: 0,
  status: 'PENDING',
  payload: {
    date: '2026-11-25',
    slot: 'DAY',
    overrideReason: 'forgot',
    records: [
      { studentId: u(101), status: 'LATE', minutesLate: 10, expectedVersion: 2 },
      { studentId: u(102), status: 'ABSENT' },
    ],
  },
  volatile: { notes: { [u(102)]: 'fever' } },
};

describe('toRequestOperation', () => {
  it('builds an ATTENDANCE_MARK with the roll version, the marks and the in-memory notes', () => {
    expect(toRequestOperation(attendance, DEVICE)).toEqual({
      deviceId: DEVICE,
      clientOperationId: u(1),
      operationType: 'ATTENDANCE_MARK',
      entityType: 'ATTENDANCE_SESSION',
      baseVersion: 4,
      clientCreatedAt: '2026-11-25T07:30:00.000Z',
      payload: {
        schoolId: SCHOOL,
        classId: CLASS,
        date: '2026-11-25',
        slot: 'DAY',
        overrideReason: 'forgot',
        records: [
          { studentId: u(101), status: 'LATE', minutesLate: 10, expectedVersion: 2 },
          { studentId: u(102), status: 'ABSENT', note: 'fever' },
        ],
      },
    });
  });

  it('omits the base version when no roll was known, and sends no note for a restarted operation', () => {
    const { volatile: _v, ...restarted } = attendance;
    const request = toRequestOperation({ ...restarted, baseVersion: null } as QueuedOperation, DEVICE);
    expect(request).not.toHaveProperty('baseVersion');
    expect(JSON.stringify(request)).not.toContain('note');
  });

  it('builds a POINT_AWARD with the operation id as the idempotency key and never a note', () => {
    const award: QueuedOperation = {
      type: 'POINT_AWARD',
      opId: u(2),
      ownerId: 'teacher-1',
      schoolId: SCHOOL,
      classId: CLASS,
      baseVersion: null,
      createdAt: 0,
      attempts: 0,
      nextAttemptAt: 0,
      status: 'PENDING',
      payload: { categoryId: CATEGORY, studentIds: [u(101), u(102)], wholeClass: false, points: 3 },
    };
    expect(toRequestOperation(award, DEVICE)).toMatchObject({
      clientOperationId: u(2),
      operationType: 'POINT_AWARD',
      entityType: 'POINT_TRANSACTION',
      payload: { schoolId: SCHOOL, classId: CLASS, categoryId: CATEGORY, studentIds: [u(101), u(102)], points: 3 },
    });
    expect(toRequestOperation(award, DEVICE).payload).not.toHaveProperty('note');
    const whole = toRequestOperation({ ...award, payload: { categoryId: CATEGORY, studentIds: [], wholeClass: true, points: null } }, DEVICE);
    expect(whole.payload).toEqual({ schoolId: SCHOOL, classId: CLASS, categoryId: CATEGORY, wholeClass: true });
  });
});

describe('readResult', () => {
  it('reads APPLIED with its version and state', () => {
    expect(readResult({ status: 'APPLIED', version: 5, state: { a: 1 }, clientOperationId: u(1) })).toEqual({ kind: 'applied', replayed: false, version: 5, state: { a: 1 } });
  });

  it('reads a DUPLICATE by what happened the first time', () => {
    expect(readResult({ status: 'DUPLICATE', originalStatus: 'APPLIED', state: {} })).toMatchObject({ kind: 'applied', replayed: true });
    expect(readResult({ status: 'DUPLICATE', state: {} })).toMatchObject({ kind: 'applied', replayed: true });
    expect(readResult({ status: 'DUPLICATE', originalStatus: 'CONFLICT', state: conflictState() })).toMatchObject({ kind: 'conflict' });
    expect(readResult({ status: 'DUPLICATE', originalStatus: 'REJECTED', problem: { code: 'class.archived', status: 409, detail: 'archived' } })).toEqual({
      kind: 'rejected',
      problem: { code: 'class.archived', status: 409, detail: 'archived' },
    });
  });

  it('reads CONFLICT and REJECTED', () => {
    expect(readResult({ status: 'CONFLICT', version: 8, state: {} })).toMatchObject({ kind: 'conflict', version: 8 });
    expect(readResult({ status: 'REJECTED', problem: { code: 'sync.operation_too_old', status: 422, detail: 'too old' } })).toEqual({
      kind: 'rejected',
      problem: { code: 'sync.operation_too_old', status: 422, detail: 'too old' },
    });
  });

  it('a transient failure is retried, not rejected', () => {
    expect(readResult({ status: 'REJECTED', retryable: true, problem: { code: 'sync.operation_failed', status: 500, detail: 'x' } })).toEqual({ kind: 'retry', reason: 'server' });
    expect(readResult({ status: 'REJECTED', retryable: true, problem: { code: 'rate_limited', status: 429, detail: 'x' } })).toEqual({ kind: 'retry', reason: 'throttled' });
  });

  it('never guesses that something went through', () => {
    expect(readResult({ status: 'SOMETHING_NEW' })).toEqual({ kind: 'retry', reason: 'server' });
    expect(readResult(null)).toEqual({ kind: 'retry', reason: 'server' });
    expect(readResult('x')).toEqual({ kind: 'retry', reason: 'server' });
  });

  it('survives a rejection without a problem', () => {
    expect(readResult({ status: 'REJECTED' })).toEqual({ kind: 'rejected', problem: { status: 422, code: 'sync.rejected', detail: '' } });
  });
});

describe('readBatchResponse', () => {
  it('matches results by operation id, not by position', () => {
    const outcome = readBatchResponse({
      results: [
        { clientOperationId: u(2), status: 'REJECTED', problem: { code: 'x', status: 404, detail: 'd' } },
        { clientOperationId: u(1), status: 'APPLIED' },
      ],
    });
    expect(outcome.kind).toBe('results');
    if (outcome.kind !== 'results') return;
    expect(outcome.results.get(u(1))).toMatchObject({ kind: 'applied' });
    expect(outcome.results.get(u(2))).toMatchObject({ kind: 'rejected' });
  });

  it('keeps everything when the body is not what it should be', () => {
    expect(readBatchResponse({})).toEqual({ kind: 'retry', reason: 'server' });
    expect(readBatchResponse(null)).toEqual({ kind: 'retry', reason: 'server' });
    expect(readBatchResponse({ results: 'no' })).toEqual({ kind: 'retry', reason: 'server' });
  });
});

describe('classifyRequestFailure', () => {
  it('reads "no answer" as network', () => {
    expect(classifyRequestFailure(undefined, undefined)).toEqual({ kind: 'retry', reason: 'network' });
  });
  it('429 waits for Retry-After, or half a minute', () => {
    expect(classifyRequestFailure(429, undefined, 45)).toEqual({ kind: 'retry', reason: 'throttled', retryAfterMs: 45_000 });
    expect(classifyRequestFailure(429, undefined)).toEqual({ kind: 'retry', reason: 'throttled', retryAfterMs: 30_000 });
  });
  it('400 and 413 refuse the request so it is sent one by one', () => {
    expect(classifyRequestFailure(413, { code: 'sync.too_many_operations', detail: 'too many' })).toEqual({
      kind: 'refused',
      status: 413,
      code: 'sync.too_many_operations',
      detail: 'too many',
    });
    expect(classifyRequestFailure(400, undefined)).toEqual({ kind: 'refused', status: 400 });
  });
  it('5xx, an expired session and any other surprise keep the operations', () => {
    for (const status of [500, 502, 503, 401, 403, 404, 408, 425, 302]) {
      expect(classifyRequestFailure(status, undefined)).toEqual({ kind: 'retry', reason: 'server' });
    }
  });
});
