import {
  cleanPayload,
  conflictFromState,
  isValidOperation,
  canMerge,
  mergeAttendance,
  mergeKeyOf,
  mergeNotes,
} from './operations';
import {
  backoffMs,
  DEFAULT_LIMITS,
  isAttendanceOp,
  type AttendancePayload,
  type BatchOutcome,
  type CancelResult,
  type ConflictState,
  type EnqueueResult,
  type FlushSummary,
  type NewOperation,
  type OperationProblem,
  type OperationResult,
  type OperationStatus,
  type QueuedOperation,
  type SyncEvent,
  type SyncLimits,
  type SyncTransport,
} from './types';

/**
 * The offline-safe outbox, as pure logic: storage, transport, clock and owner are injected, so the same code runs
 * on the device (SQLite, `POST /sync`) and in tests (and against a real API from a script).
 *
 * The contract that keeps a teacher from marking or awarding twice: every user action gets one `opId` (the API's
 * `clientOperationId`) when it is queued, and the row is kept until the server answered. A repeat of the same id
 * is answered with the original outcome (DUPLICATE), so anything unclear (offline, a timeout, a 5xx, a lost
 * answer, an app restart) is solved by sending the SAME operation again. An operation that was ever handed to
 * the network is never changed again, because the server may already have applied it.
 *
 * What the server decides is kept for the teacher instead of being retried: a CONFLICT (a colleague marked the
 * same student differently), a REJECTED operation (it can never succeed as sent) and an EXPIRED one (it waited
 * longer than allowed) stay as rows with their status until the teacher resolves or dismisses them.
 */

export interface OutboxStorage {
  /** Every row, oldest first (insertion order). */
  load(): Promise<QueuedOperation[]>;
  insert(item: QueuedOperation): Promise<void>;
  updateAttempt(opId: string, patch: { attempts: number; nextAttemptAt: number }): Promise<void>;
  /** Edits that were merged into a not yet sent attendance operation. */
  updatePayload(opId: string, patch: { payload: AttendancePayload; baseVersion: number | null }): Promise<void>;
  /** A pending operation became CONFLICT, REJECTED or EXPIRED. */
  settle(
    opId: string,
    patch: { status: Exclude<OperationStatus, 'PENDING'>; problem: OperationProblem | null; conflict: ConflictState | null; settledAt: number },
  ): Promise<void>;
  updateConflict(opId: string, conflict: ConflictState): Promise<void>;
  remove(opId: string): Promise<void>;
  clear(): Promise<void>;
}

export interface SyncEngineOptions {
  readonly storage: OutboxStorage;
  readonly transport: SyncTransport;
  readonly now: () => number;
  /** The signed-in user's id, or null when nobody is (nothing is sent then). */
  readonly getOwnerId: () => string | null;
  readonly limits?: SyncLimits;
}

export interface EnqueueOptions {
  /** Memory-only data that is merged into the request when the operation is sent (attendance notes). */
  readonly volatile?: QueuedOperation['volatile'];
}

interface Totals {
  applied: number;
  conflicts: number;
  rejected: number;
  expired: number;
  stoppedOffline: boolean;
}

type Step = 'continue' | 'stop' | 'single';

export class SyncEngine {
  private items: QueuedOperation[] = [];
  private pendingView: readonly QueuedOperation[] = [];
  private attentionView: readonly QueuedOperation[] = [];
  private loaded = false;
  private readonly inFlight = new Set<string>();
  private running: Promise<FlushSummary> | null = null;
  private again = false;
  private forceAgain = false;
  /** Bumped by `clear()`: results of calls that were in the air at that moment are ignored. */
  private generation = 0;
  private tail: Promise<unknown> = Promise.resolve();
  private readonly listeners = new Set<() => void>();
  private readonly eventListeners = new Set<(event: SyncEvent) => void>();
  private readonly limits: SyncLimits;

  constructor(private readonly options: SyncEngineOptions) {
    this.limits = options.limits ?? DEFAULT_LIMITS;
  }

  /** Runs `task` after every earlier mutation: the in-memory rows and the storage change in the same order. */
  private exclusive<T>(task: () => Promise<T>): Promise<T> {
    const run = this.tail.then(task, task);
    this.tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  /** Loads the persisted rows (once). Safe to call again. */
  async init(): Promise<void> {
    if (this.loaded) return;
    const loaded = await this.options.storage.load();
    if (this.loaded) return; // cleared or initialised while loading
    this.items = loaded;
    this.loaded = true;
    this.emitChange();
  }

  /** Operations waiting to be sent, oldest first. A stable snapshot until something changes. */
  pending(): readonly QueuedOperation[] {
    return this.pendingView;
  }

  /** Conflicts, rejections and expired operations the teacher still has to look at. */
  attention(): readonly QueuedOperation[] {
    return this.attentionView;
  }

  count(): number {
    return this.pendingView.length;
  }

  /** Called whenever the rows change (a count for the indicator, a list for the optimistic views). */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onEvent(listener: (event: SyncEvent) => void): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  enqueue(operation: NewOperation, options: EnqueueOptions = {}): Promise<EnqueueResult> {
    return this.exclusive(async () => {
      await this.init();
      const clean = cleanPayload(operation);
      if (!isValidOperation(clean)) return 'invalid';
      if (this.items.some((item) => item.opId === clean.opId)) return 'duplicate';

      const merged = await this.tryMerge(clean, options);
      if (merged) return 'merged';

      if (this.items.length >= this.limits.maxItems) return 'full';
      const item = {
        ...clean,
        createdAt: this.options.now(),
        attempts: 0,
        nextAttemptAt: 0,
        status: 'PENDING' as const,
        ...(options.volatile ? { volatile: options.volatile } : {}),
      } as QueuedOperation;
      await this.options.storage.insert(item);
      this.items = [...this.items, item];
      this.emitChange();
      return 'queued';
    });
  }

  private async tryMerge(incoming: NewOperation, options: EnqueueOptions): Promise<boolean> {
    const key = mergeKeyOf(incoming);
    if (key === null || incoming.type !== 'ATTENDANCE_MARK') return false;
    // The newest candidate: the operation an earlier tap made. Only one that never left the device is safe to edit.
    const target = [...this.items]
      .reverse()
      .find((item) => item.status === 'PENDING' && mergeKeyOf(item) === key);
    if (!target || !isAttendanceOp(target) || target.attempts > 0 || this.inFlight.has(target.opId)) return false;
    // The same roll but another subject or group: its own operation, after the older one.
    if (!canMerge(target, incoming)) return false;

    const payload = mergeAttendance(target, { ...incoming, type: 'ATTENDANCE_MARK', payload: incoming.payload });
    const candidate = { ...target, payload };
    if (!isValidOperation(candidate)) return false;
    await this.options.storage.updatePayload(target.opId, { payload, baseVersion: target.baseVersion });
    const notes = mergeNotes(target.volatile?.notes, options.volatile?.notes);
    const updated: QueuedOperation = { ...candidate, ...(notes ? { volatile: { notes } } : {}) };
    this.items = this.items.map((item) => (item.opId === target.opId ? updated : item));
    this.emitChange();
    return true;
  }

  /**
   * Withdraws an operation that was not sent yet (the teacher tapped "undo" while offline). One that was handed
   * to the network may have been applied, so it is not removed: the caller undoes it once it is confirmed.
   */
  cancel(opId: string): Promise<CancelResult> {
    return this.exclusive(async () => {
      const item = this.items.find((candidate) => candidate.opId === opId && candidate.status === 'PENDING');
      if (!item) return 'not-found';
      if (this.inFlight.has(opId) || item.attempts > 0) return 'in-flight';
      await this.options.storage.remove(opId);
      this.items = this.items.filter((candidate) => candidate.opId !== opId);
      this.emitChange();
      return 'cancelled';
    });
  }

  /** Forgets a conflict, rejection or expired operation. A pending one is left alone (use `cancel`). */
  dismiss(opId: string): Promise<void> {
    return this.exclusive(async () => {
      const item = this.items.find((candidate) => candidate.opId === opId);
      if (!item || item.status === 'PENDING') return;
      await this.options.storage.remove(opId);
      this.items = this.items.filter((candidate) => candidate.opId !== opId);
      this.emitChange();
    });
  }

  /** The teacher settled some of the clashes: keeps the rest, or forgets the row when nothing is left. */
  updateConflict(opId: string, conflict: ConflictState | null): Promise<void> {
    return this.exclusive(async () => {
      const item = this.items.find((candidate) => candidate.opId === opId && candidate.status === 'CONFLICT');
      if (!item) return;
      if (!conflict || conflict.entries.length === 0) {
        await this.options.storage.remove(opId);
        this.items = this.items.filter((candidate) => candidate.opId !== opId);
      } else {
        await this.options.storage.updateConflict(opId, conflict);
        this.items = this.items.map((candidate) => (candidate.opId === opId ? { ...candidate, conflict } : candidate));
      }
      this.emitChange();
    });
  }

  /** Sign-out: forgets everything, also what is being sent right now. */
  async clear(): Promise<void> {
    this.generation++;
    this.items = [];
    this.loaded = true;
    this.inFlight.clear();
    // Not queued behind other work: a wipe must not wait for a slow write, and it wins over what is queued.
    await this.options.storage.clear();
    this.emitChange();
  }

  /**
   * Sends what is due, oldest first. One flush runs at a time (a second call joins it and makes it look once
   * more). `force` ignores the backoff: for "reconnected", "back in the foreground" and "send now".
   */
  flush(force = false): Promise<FlushSummary> {
    if (this.running) {
      this.again = true;
      if (force) this.forceAgain = true;
      return this.running;
    }
    this.running = this.run(force).finally(() => {
      this.running = null;
    });
    return this.running;
  }

  private async run(force: boolean): Promise<FlushSummary> {
    await this.init();
    const total: Totals = { applied: 0, conflicts: 0, rejected: 0, expired: 0, stoppedOffline: false };
    let forced = force;
    for (;;) {
      this.again = false;
      this.forceAgain = false;
      const stoppedOffline = await this.pass(forced, total);
      if (stoppedOffline || !this.again) break;
      forced = this.forceAgain;
    }
    return { applied: total.applied, conflicts: total.conflicts, rejected: total.rejected, expired: total.expired, remaining: this.pendingView.length };
  }

  /** One sweep over what is due. Returns true when it stopped because the network was unusable. */
  private async pass(force: boolean, total: Totals): Promise<boolean> {
    const generation = this.generation;
    const ownerId = this.options.getOwnerId();
    if (ownerId === null) return false;

    await this.exclusive(() => this.housekeeping(ownerId, generation, total));
    let single = false;
    for (;;) {
      if (generation !== this.generation) return false;
      const batch = await this.exclusive(() => this.takeBatch(force, single, ownerId, generation));
      if (batch.length === 0) return false;

      for (const item of batch) this.inFlight.add(item.opId);
      let outcome: BatchOutcome;
      try {
        outcome = await this.options.transport(batch);
      } catch {
        outcome = { kind: 'retry', reason: 'network' };
      }
      if (generation !== this.generation) return false; // signed out while the call was in the air

      const step = await this.exclusive(async () => {
        for (const item of batch) this.inFlight.delete(item.opId);
        if (generation !== this.generation) return 'stop' as Step;
        return this.apply(batch, outcome, total, generation);
      });
      if (step === 'stop') return outcome.kind === 'retry' && (outcome.reason === 'network' || outcome.reason === 'offline');
      if (step === 'single') single = true;
    }
  }

  /** Other users' rows go unsent, old pending ones expire, invalid ones are refused, old attention rows are forgotten. */
  private async housekeeping(ownerId: string, generation: number, total: Totals): Promise<void> {
    const now = this.options.now();
    for (const item of [...this.items]) {
      if (generation !== this.generation) return;
      if (item.ownerId !== ownerId) {
        await this.remove(item, { type: 'discarded', item });
      } else if (item.status === 'PENDING') {
        if (now - item.createdAt > this.limits.maxAgeMs[item.type]) {
          await this.settle(item, 'EXPIRED', { status: 0, code: 'sync.expired', detail: '' }, null, { type: 'expired', item });
          total.expired++;
        } else if (!isValidOperation(item)) {
          // Rows from an older build or a damaged database: never sent, the teacher is told.
          const problem = { status: 0, code: 'sync.invalid_operation', detail: '' };
          await this.settle(item, 'REJECTED', problem, null, { type: 'rejected', item, problem });
          total.rejected++;
        }
      } else if (item.settledAt !== undefined && now - item.settledAt > this.limits.attentionRetentionMs) {
        await this.remove(item, null);
      }
    }
  }

  /** The next requests' worth of due operations, in order, marked as handed to the network. */
  private async takeBatch(force: boolean, single: boolean, ownerId: string, generation: number): Promise<QueuedOperation[]> {
    if (generation !== this.generation) return [];
    const now = this.options.now();
    const batch: QueuedOperation[] = [];
    let bytes = 0;
    const cap = single ? 1 : this.limits.maxBatch;
    for (const item of this.items) {
      if (item.status !== 'PENDING' || item.ownerId !== ownerId) continue;
      // Strict order: an operation that is not due yet holds back everything after it.
      if (!force && item.nextAttemptAt > now) break;
      const size = JSON.stringify(item.payload).length + 400;
      if (batch.length > 0 && (batch.length >= cap || bytes + size > this.limits.maxBatchBytes)) break;
      batch.push(item);
      bytes += size;
    }
    if (batch.length === 0) return batch;

    // Persist "handed over" first: if the app dies mid-request the row is no longer editable after a restart.
    const sending = batch.map((item) => ({ ...item, attempts: item.attempts + 1 }));
    for (const item of sending) {
      await this.options.storage.updateAttempt(item.opId, { attempts: item.attempts, nextAttemptAt: item.nextAttemptAt });
    }
    const byId = new Map(sending.map((item) => [item.opId, item]));
    this.items = this.items.map((item) => byId.get(item.opId) ?? item);
    this.emitChange();
    return sending;
  }

  private async apply(batch: readonly QueuedOperation[], outcome: BatchOutcome, total: Totals, generation: number): Promise<Step> {
    if (outcome.kind === 'retry') {
      if (outcome.reason === 'offline') {
        // Nothing was sent: hand the attempts back so the operations stay editable.
        for (const item of batch) {
          const before = item.attempts - 1;
          await this.options.storage.updateAttempt(item.opId, { attempts: before, nextAttemptAt: item.nextAttemptAt });
          this.items = this.items.map((current) => (current.opId === item.opId ? { ...current, attempts: before } : current));
        }
        this.emitChange();
        return 'stop';
      }
      for (const item of batch) await this.scheduleRetry(item, outcome.retryAfterMs);
      return 'stop';
    }

    if (outcome.kind === 'refused') {
      if (batch.length > 1) return 'single';
      const item = batch[0]!;
      const problem = { status: outcome.status, code: outcome.code ?? 'sync.refused', detail: outcome.detail ?? '' };
      await this.settle(item, 'REJECTED', problem, null, { type: 'rejected', item, problem });
      total.rejected++;
      return 'continue';
    }

    let retried = false;
    for (const item of batch) {
      if (generation !== this.generation) return 'stop';
      const result: OperationResult = outcome.results.get(item.opId) ?? { kind: 'retry', reason: 'server' };
      switch (result.kind) {
        case 'applied':
          await this.remove(item, { type: 'applied', item, replayed: result.replayed, version: result.version, state: result.state });
          total.applied++;
          break;
        case 'conflict': {
          const conflict = conflictFromState(result.state);
          if (conflict) {
            await this.settle(item, 'CONFLICT', null, conflict, { type: 'conflict', item, conflict, state: result.state });
            total.conflicts++;
          } else {
            const problem = { status: 409, code: 'sync.conflict_unreadable', detail: '' };
            await this.settle(item, 'REJECTED', problem, null, { type: 'rejected', item, problem });
            total.rejected++;
          }
          break;
        }
        case 'rejected':
          await this.settle(item, 'REJECTED', result.problem, null, { type: 'rejected', item, problem: result.problem });
          total.rejected++;
          break;
        case 'retry':
          retried = true;
          await this.scheduleRetry(item, result.retryAfterMs);
          break;
      }
    }
    // After a transient failure the rest waits too: the order of the queue stays intact.
    return retried ? 'stop' : 'continue';
  }

  private async scheduleRetry(item: QueuedOperation, retryAfterMs: number | undefined): Promise<void> {
    const wait = Math.max(backoffMs(item.attempts), retryAfterMs ?? 0);
    const nextAttemptAt = this.options.now() + wait;
    await this.options.storage.updateAttempt(item.opId, { attempts: item.attempts, nextAttemptAt });
    this.items = this.items.map((current) => (current.opId === item.opId ? { ...current, nextAttemptAt } : current));
    this.emitChange();
  }

  /** Removes a row for good. The event goes out first, then the change: a screen sees both in one render. */
  private async remove(item: QueuedOperation, event: SyncEvent | null): Promise<void> {
    await this.options.storage.remove(item.opId);
    this.items = this.items.filter((current) => current.opId !== item.opId);
    if (event) this.emitEvent(event);
    this.emitChange();
  }

  /** Keeps the row, with the status the server (or the clock) gave it. */
  private async settle(
    item: QueuedOperation,
    status: Exclude<OperationStatus, 'PENDING'>,
    problem: OperationProblem | null,
    conflict: ConflictState | null,
    event: SyncEvent,
  ): Promise<void> {
    const settledAt = this.options.now();
    await this.options.storage.settle(item.opId, { status, problem, conflict, settledAt });
    const { volatile: _notes, ...rest } = item;
    const settled = { ...rest, status, settledAt, ...(problem ? { problem } : {}), ...(conflict ? { conflict } : {}) } as QueuedOperation;
    this.items = this.items.map((current) => (current.opId === item.opId ? settled : current));
    this.emitEvent(event);
    this.emitChange();
  }

  private emitEvent(event: SyncEvent): void {
    for (const listener of this.eventListeners) listener(event);
  }

  private emitChange(): void {
    this.pendingView = this.items.filter((item) => item.status === 'PENDING');
    this.attentionView = this.items.filter((item) => item.status !== 'PENDING');
    for (const listener of this.listeners) listener();
  }
}
