import { parseRetryAfter } from '@/lib/api-error';

import type { AwardConfirmation, SendOutcome } from './award-queue';

/** Statuses that look like "4xx" but only mean "not now": the same call may succeed later. */
const RETRYABLE_CLIENT_STATUSES: ReadonlySet<number> = new Set([401, 408, 425, 429]);

/**
 * Turns a failed call into what the queue does next. No status means the request never got an answer (offline,
 * DNS, timeout). 5xx and "not now" statuses keep the item for another try; any other 4xx is a business answer
 * (the student left the class, the skill was switched off, ...): final, and the teacher is told.
 */
export function classifyFailure(
  status: number | undefined,
  problem: { code?: string | undefined; detail?: string | undefined } | undefined,
  retryAfterSeconds?: number,
): SendOutcome {
  if (status === undefined) return { kind: 'retry', reason: 'network' };
  if (status === 429 || status === 408 || status === 425) {
    return {
      kind: 'retry',
      reason: 'throttled',
      ...(retryAfterSeconds !== undefined ? { retryAfterMs: retryAfterSeconds * 1000 } : {}),
    };
  }
  // 401: the session could not be renewed right now; the sign-out path clears the queue when it truly ended.
  if (RETRYABLE_CLIENT_STATUSES.has(status) || status >= 500) return { kind: 'retry', reason: 'server' };
  if (status >= 400) {
    return {
      kind: 'rejected',
      status,
      ...(problem?.code ? { code: problem.code } : {}),
      ...(problem?.detail ? { detail: problem.detail } : {}),
    };
  }
  // A 3xx or an unexpected 1xx/2xx without a body: do not guess that the award went through.
  return { kind: 'retry', reason: 'server' };
}

/** The part of the API's `AwardResult` the queue and the undo snackbar need. */
export interface AwardResultLike {
  batchId?: string | undefined;
  replayed?: boolean | undefined;
  undoableUntil?: string | undefined;
  transactions: readonly { id: string }[];
}

/** 201 and 200 + `replayed` both mean "the server has it": the item is done. */
export function confirmation(result: AwardResultLike): AwardConfirmation {
  return {
    batchId: result.batchId ?? null,
    undoableUntil: result.undoableUntil ?? null,
    transactionIds: result.transactions.map((transaction) => transaction.id),
  };
}

/** Whether the undo button may still be offered (the API has the last word: it answers 403 after the window). */
export function canUndoAt(undoableUntil: string | null | undefined, nowMs: number): boolean {
  if (!undoableUntil) return false;
  const until = Date.parse(undoableUntil);
  return Number.isFinite(until) && nowMs < until;
}

/** The part of an openapi-fetch result the transport reads. */
export interface AwardCallResult {
  data?: AwardResultLike | undefined;
  error?: unknown;
  response: { status: number; headers?: { get(name: string): string | null } | undefined };
}

function problemFields(error: unknown): { code?: string | undefined; detail?: string | undefined } | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const record = error as Record<string, unknown>;
  return {
    code: typeof record.code === 'string' ? record.code : undefined,
    detail: typeof record.detail === 'string' ? record.detail : undefined,
  };
}

/**
 * The queue's reading of the award call: 201 (new) and 200 (replay of the op id) with a body are both
 * confirmations; everything else goes through `classifyFailure`.
 */
export function outcomeFromResult(result: AwardCallResult): SendOutcome {
  const status = result.response.status;
  if ((status === 200 || status === 201) && result.data) {
    return { kind: 'confirmed', replayed: status === 200 || result.data.replayed === true, confirmation: confirmation(result.data) };
  }
  return classifyFailure(status, problemFields(result.error), parseRetryAfter(result.response.headers?.get('Retry-After')));
}
