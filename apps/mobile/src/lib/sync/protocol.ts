import { ENTITY_TYPE, type BatchOutcome, type OperationProblem, type OperationResult, type QueuedOperation } from './types';

/**
 * The wire format of `POST /api/v1/sync` (see the backend's `sync` module): pure functions between the outbox's
 * rows and the request and response bodies.
 */

export interface RequestOperation {
  deviceId: string;
  clientOperationId: string;
  operationType: string;
  entityType: string;
  baseVersion?: number;
  payload: Record<string, unknown>;
  clientCreatedAt: string;
}

/** The API's `SyncOperationRequest` for a row. The notes (memory only) are added here, at send time. */
export function toRequestOperation(op: QueuedOperation, deviceId: string): RequestOperation {
  const base = {
    deviceId,
    clientOperationId: op.opId,
    operationType: op.type,
    entityType: ENTITY_TYPE[op.type],
    ...(op.baseVersion !== null ? { baseVersion: op.baseVersion } : {}),
    clientCreatedAt: new Date(op.createdAt).toISOString(),
  };
  if (op.type === 'ATTENDANCE_MARK') {
    const notes = op.volatile?.notes ?? {};
    return {
      ...base,
      payload: {
        schoolId: op.schoolId,
        classId: op.classId,
        date: op.payload.date,
        slot: op.payload.slot,
        ...(op.payload.subjectId !== undefined ? { subjectId: op.payload.subjectId } : {}),
        ...(op.payload.groupId !== undefined ? { groupId: op.payload.groupId } : {}),
        ...(op.payload.overrideReason !== undefined ? { overrideReason: op.payload.overrideReason } : {}),
        records: op.payload.records.map((record) => ({
          studentId: record.studentId,
          status: record.status,
          ...(record.minutesLate !== undefined ? { minutesLate: record.minutesLate } : {}),
          ...(record.expectedVersion !== undefined ? { expectedVersion: record.expectedVersion } : {}),
          ...(notes[record.studentId] ? { note: notes[record.studentId] } : {}),
        })),
      },
    };
  }
  return {
    ...base,
    payload: {
      schoolId: op.schoolId,
      classId: op.classId,
      categoryId: op.payload.categoryId,
      ...(op.payload.wholeClass ? { wholeClass: true } : { studentIds: [...op.payload.studentIds] }),
      ...(op.payload.points !== null ? { points: op.payload.points } : {}),
    },
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function problemOf(value: unknown, fallbackStatus: number): OperationProblem {
  const record = asRecord(value);
  return {
    status: typeof record?.status === 'number' ? record.status : fallbackStatus,
    code: typeof record?.code === 'string' ? record.code : 'sync.rejected',
    detail: typeof record?.detail === 'string' ? record.detail : '',
  };
}

function versionOf(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** One entry of the API's `results`, as the engine understands it. */
export function readResult(raw: unknown): OperationResult {
  const result = asRecord(raw);
  if (!result) return { kind: 'retry', reason: 'server' };
  const version = versionOf(result.version);
  const state = result.state ?? null;

  // A transient failure (429, 5xx): nothing was stored, the same operation may be sent again.
  if (result.retryable === true) {
    const problem = asRecord(result.problem);
    return { kind: 'retry', reason: problem?.status === 429 ? 'throttled' : 'server' };
  }

  switch (result.status) {
    case 'APPLIED':
      return { kind: 'applied', replayed: false, version, state };
    case 'CONFLICT':
      return { kind: 'conflict', version, state };
    case 'REJECTED':
      return { kind: 'rejected', problem: problemOf(result.problem, 422) };
    case 'DUPLICATE':
      // The first answer wins: a replay of a conflict is still a conflict, of a rejection still a rejection.
      switch (result.originalStatus) {
        case 'CONFLICT':
          return { kind: 'conflict', version, state };
        case 'REJECTED':
          return { kind: 'rejected', problem: problemOf(result.problem, 422) };
        default:
          return { kind: 'applied', replayed: true, version, state };
      }
    default:
      return { kind: 'retry', reason: 'server' };
  }
}

/** The body of a 200 answer. Results are matched by operation id, not by position. */
export function readBatchResponse(body: unknown): BatchOutcome {
  const results = asRecord(body)?.results;
  if (!Array.isArray(results)) return { kind: 'retry', reason: 'server' };
  const byId = new Map<string, OperationResult>();
  for (const raw of results) {
    const id = asRecord(raw)?.clientOperationId;
    if (typeof id === 'string') byId.set(id, readResult(raw));
  }
  return { kind: 'results', results: byId };
}

/**
 * What a failed call (no 200) means for the batch. No status: it never got an answer (offline, DNS, timeout).
 * 429 and the "not now" statuses keep everything. 400 and 413 refuse the whole request: the engine sends the
 * operations one by one to find the culprit. Any other answer is not a verdict on the operations (a proxy, an
 * expired session): they are kept.
 */
export function classifyRequestFailure(
  status: number | undefined,
  problem: { code?: string | undefined; detail?: string | undefined } | undefined,
  retryAfterSeconds?: number,
): BatchOutcome {
  if (status === undefined) return { kind: 'retry', reason: 'network' };
  if (status === 429) {
    return { kind: 'retry', reason: 'throttled', ...(retryAfterSeconds !== undefined ? { retryAfterMs: retryAfterSeconds * 1000 } : { retryAfterMs: 30_000 }) };
  }
  if (status === 400 || status === 413) {
    return {
      kind: 'refused',
      status,
      ...(problem?.code ? { code: problem.code } : {}),
      ...(problem?.detail ? { detail: problem.detail } : {}),
    };
  }
  return { kind: 'retry', reason: 'server' };
}
