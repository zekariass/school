/**
 * Shapes of the offline outbox: the operations a teacher queued while offline (or just before the network
 * answered), what the sync endpoint said about them, and what the engine tells the screens.
 *
 * What an operation stores is deliberately minimal: ids, statuses, numbers and times. Never a name, a token or a
 * free-text note about a child (see `QueuedOperation.volatile` for the one memory-only exception).
 */

export type OperationType = 'ATTENDANCE_MARK' | 'POINT_AWARD';

/** The API's `entityType` for each operation type. */
export const ENTITY_TYPE: Readonly<Record<OperationType, string>> = {
  ATTENDANCE_MARK: 'ATTENDANCE_SESSION',
  POINT_AWARD: 'POINT_TRANSACTION',
};

export const ATTENDANCE_STATUSES = ['PRESENT', 'ABSENT', 'LATE', 'EXCUSED', 'LEFT_EARLY'] as const;
export type AttendanceStatus = (typeof ATTENDANCE_STATUSES)[number];

export function isAttendanceStatus(value: unknown): value is AttendanceStatus {
  return typeof value === 'string' && (ATTENDANCE_STATUSES as readonly string[]).includes(value);
}

/** One student's mark, as queued. No note: notes live in `volatile` only. */
export interface AttendanceMark {
  readonly studentId: string;
  readonly status: AttendanceStatus;
  /** 1 to 600, only with LATE. */
  readonly minutesLate?: number;
  /** The version of the student's record the teacher saw (absent when the student had no record). */
  readonly expectedVersion?: number;
}

export interface AttendancePayload {
  /** School-local calendar date (`YYYY-MM-DD`): never the device's date. */
  readonly date: string;
  readonly slot: string;
  readonly records: readonly AttendanceMark[];
  /** Why a locked roll is edited (the lead teacher and managers only). */
  readonly overrideReason?: string;
}

export interface AwardPayload {
  readonly categoryId: string;
  /** Empty when `wholeClass`. */
  readonly studentIds: readonly string[];
  readonly wholeClass: boolean;
  /** Null: the skill's default points. */
  readonly points: number | null;
}

/** `PENDING` is sent next; the others wait for the teacher (they are never sent again). */
export type OperationStatus = 'PENDING' | 'CONFLICT' | 'REJECTED' | 'EXPIRED';

export interface OperationProblem {
  readonly status: number;
  readonly code: string;
  /** The API's localized detail (empty for a local failure). */
  readonly detail: string;
}

/** What the server holds for a student whose mark clashed, and what this device sent. */
export interface ConflictEntry {
  readonly studentId: string;
  readonly stored: {
    readonly status: AttendanceStatus;
    readonly minutesLate: number | null;
    readonly version: number | null;
    /** The user id of whoever marked it (a name is looked up from the class teachers when it is shown). */
    readonly recordedBy: string | null;
  };
  readonly submitted: { readonly status: AttendanceStatus; readonly minutesLate: number | null };
}

export interface ConflictState {
  readonly sessionVersion: number | null;
  readonly entries: readonly ConflictEntry[];
}

interface OperationBase {
  /** The API's `clientOperationId`: one per user action, kept until the server answered. */
  readonly opId: string;
  /** The signed-in user's id: an outbox never sends under somebody else's session. */
  readonly ownerId: string;
  readonly schoolId: string;
  readonly classId: string;
  /** ATTENDANCE_MARK: the roll's `sessionVersion` the teacher edited from (null: no roll was known). */
  readonly baseVersion: number | null;
  /** Epoch ms when it was queued (the age cap counts from here; it is also the API's `clientCreatedAt`). */
  readonly createdAt: number;
  /** How many times it was handed to the network. Above zero it never changes again (see `merge`). */
  readonly attempts: number;
  /** Epoch ms before which a periodic retry leaves it alone (backoff). */
  readonly nextAttemptAt: number;
  readonly status: OperationStatus;
  /** REJECTED, or EXPIRED. */
  readonly problem?: OperationProblem;
  /** CONFLICT: the clashing marks. */
  readonly conflict?: ConflictState;
  /** Epoch ms when it left PENDING (attention rows are purged after a week). */
  readonly settledAt?: number;
  /**
   * Memory only, never given to the storage: data that must not be written to disk (an attendance note about a
   * child). It is lost when the app closes before the operation was sent; the operation itself is not.
   */
  readonly volatile?: { readonly notes?: Readonly<Record<string, string>> };
}

export type QueuedAttendance = OperationBase & { readonly type: 'ATTENDANCE_MARK'; readonly payload: AttendancePayload };
export type QueuedAward = OperationBase & { readonly type: 'POINT_AWARD'; readonly payload: AwardPayload };
export type QueuedOperation = QueuedAttendance | QueuedAward;

export const isAttendanceOp = (op: QueuedOperation): op is QueuedAttendance => op.type === 'ATTENDANCE_MARK';
export const isAwardOp = (op: QueuedOperation): op is QueuedAward => op.type === 'POINT_AWARD';

type Bookkeeping = 'createdAt' | 'attempts' | 'nextAttemptAt' | 'status' | 'problem' | 'conflict' | 'settledAt';

/** What a caller gives `enqueue`; the engine adds the bookkeeping. */
export type NewOperation = Omit<QueuedAttendance, Bookkeeping> | Omit<QueuedAward, Bookkeeping>;

/** What the server answered for one operation (already read from the API's result). */
export type OperationResult =
  /** APPLIED, or a DUPLICATE of an applied operation (`replayed`). */
  | { readonly kind: 'applied'; readonly replayed: boolean; readonly version: number | null; readonly state: unknown }
  /** Some marks clashed with what a colleague wrote: nothing was overwritten, the others were written. */
  | { readonly kind: 'conflict'; readonly version: number | null; readonly state: unknown }
  /** Never succeeds as sent. */
  | { readonly kind: 'rejected'; readonly problem: OperationProblem }
  /** A transient failure (429/5xx on that operation): not stored, send the same operation again. */
  | { readonly kind: 'retry'; readonly reason: 'server' | 'throttled'; readonly retryAfterMs?: number };

export type BatchOutcome =
  /** The server answered; `results` is keyed by `opId`. An operation without a result is retried. */
  | { readonly kind: 'results'; readonly results: ReadonlyMap<string, OperationResult> }
  /**
   * Keep everything. `network`: the call failed, so the rest of the queue would fail too (the server may or may
   * not have seen it). `offline`: the device knew it had no connection and sent nothing at all.
   */
  | { readonly kind: 'retry'; readonly reason: 'network' | 'offline' | 'server' | 'throttled'; readonly retryAfterMs?: number }
  /** The whole request was refused for good (400, 413): sent one by one, the bad operation is found. */
  | { readonly kind: 'refused'; readonly status: number; readonly code?: string; readonly detail?: string };

export type SyncTransport = (operations: readonly QueuedOperation[]) => Promise<BatchOutcome>;

export interface SyncLimits {
  /** Rows kept at most (pending and waiting for the teacher); further operations are refused, never dropped. */
  readonly maxItems: number;
  /** A pending operation older than this is given up, and the teacher is told. Below the server's 30 days. */
  readonly maxAgeMs: Readonly<Record<OperationType, number>>;
  /** Operations per request (the server's cap is 100). */
  readonly maxBatch: number;
  /** Payload bytes per request (the server's cap is 1 MiB). */
  readonly maxBatchBytes: number;
  /** An operation waiting for the teacher (conflict, rejection, expiry) is forgotten after this. */
  readonly attentionRetentionMs: number;
}

const HOUR = 60 * 60 * 1000;

export const DEFAULT_LIMITS: SyncLimits = {
  maxItems: 300,
  maxAgeMs: { ATTENDANCE_MARK: 7 * 24 * HOUR, POINT_AWARD: 24 * HOUR },
  maxBatch: 100,
  maxBatchBytes: 900_000,
  attentionRetentionMs: 7 * 24 * HOUR,
};

export type SyncEvent =
  | { readonly type: 'applied'; readonly item: QueuedOperation; readonly replayed: boolean; readonly version: number | null; readonly state: unknown }
  | { readonly type: 'conflict'; readonly item: QueuedOperation; readonly conflict: ConflictState; readonly state: unknown }
  | { readonly type: 'rejected'; readonly item: QueuedOperation; readonly problem: OperationProblem }
  | { readonly type: 'expired'; readonly item: QueuedOperation }
  /** Removed without being sent: it belonged to another user's session. */
  | { readonly type: 'discarded'; readonly item: QueuedOperation };

export interface FlushSummary {
  readonly applied: number;
  readonly conflicts: number;
  readonly rejected: number;
  readonly expired: number;
  /** Operations still waiting to be sent afterwards. */
  readonly remaining: number;
}

export type EnqueueResult = 'queued' | 'merged' | 'duplicate' | 'full' | 'invalid';
export type CancelResult = 'cancelled' | 'in-flight' | 'not-found';

/** 2 s, 4 s, 8 s ... capped at 5 minutes. */
export function backoffMs(attempts: number): number {
  const n = Math.max(1, Math.floor(attempts));
  return Math.min(5 * 60_000, 2_000 * 2 ** Math.min(n - 1, 10));
}

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID_PATTERN.test(value);
