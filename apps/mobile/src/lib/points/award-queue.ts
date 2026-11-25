/**
 * The offline-safe award queue, as pure logic: storage, transport, clock and owner are injected, so the same code
 * runs on the device (SQLite, the API) and in tests.
 *
 * The contract that keeps teachers from awarding twice: every user action gets one `opId` (the API's
 * `clientOpId`) when it is queued; the item is only removed when the server confirmed it (201, or 200 with
 * `replayed`) or answered with a final 4xx. Anything else (offline, timeout, 5xx, 408/425/429) keeps the item and
 * repeats the call with the SAME id, and the server answers a repeat with the original rows.
 *
 * What is stored is deliberately minimal: ids, category, points, the op id and timestamps. Never a note, a name
 * or a token (an award with a note is not queued at all).
 */

export interface QueuedAward {
  /** The API's `clientOpId`: one per user action, kept until the server confirmed. */
  readonly opId: string;
  /** The signed-in teacher's user id: a queue never sends under somebody else's session. */
  readonly ownerId: string;
  readonly schoolId: string;
  readonly classId: string;
  readonly categoryId: string;
  /** Empty when `wholeClass`. */
  readonly studentIds: readonly string[];
  readonly wholeClass: boolean;
  /** Null: the category's default points. */
  readonly points: number | null;
  /** Epoch ms when it was queued (the age cap counts from here). */
  readonly createdAt: number;
  readonly attempts: number;
  /** Epoch ms before which a periodic retry leaves the item alone (backoff). */
  readonly nextAttemptAt: number;
}

export interface AwardQueueStorage {
  /** Every item, oldest first (insertion order). */
  load(): Promise<QueuedAward[]>;
  insert(item: QueuedAward): Promise<void>;
  update(opId: string, patch: { attempts: number; nextAttemptAt: number }): Promise<void>;
  remove(opId: string): Promise<void>;
  clear(): Promise<void>;
}

/** What the API said about a confirmed award. Ids and times only. */
export interface AwardConfirmation {
  readonly batchId: string | null;
  readonly undoableUntil: string | null;
  readonly transactionIds: readonly string[];
}

export type SendOutcome =
  | { readonly kind: 'confirmed'; readonly replayed: boolean; readonly confirmation: AwardConfirmation }
  /** A final 4xx: retrying cannot help. `detail` is the API's localized problem detail. */
  | { readonly kind: 'rejected'; readonly status: number; readonly code?: string; readonly detail?: string }
  /** Keep the item. `network`: nothing reached the server, so the rest of the queue would fail too. */
  | { readonly kind: 'retry'; readonly reason: 'network' | 'server' | 'throttled'; readonly retryAfterMs?: number };

export type AwardTransport = (item: QueuedAward) => Promise<SendOutcome>;

export interface QueueLimits {
  /** Items kept at most; further awards are refused ("queue full"), never silently dropped. */
  readonly maxItems: number;
  /** An award older than this is given up, and the teacher is told. */
  readonly maxAgeMs: number;
}

export const DEFAULT_LIMITS: QueueLimits = { maxItems: 200, maxAgeMs: 24 * 60 * 60 * 1000 };

/** 2 s, 4 s, 8 s ... capped at 5 minutes. */
export function backoffMs(attempts: number): number {
  const n = Math.max(1, Math.floor(attempts));
  return Math.min(5 * 60_000, 2_000 * 2 ** Math.min(n - 1, 10));
}

export type NewAward = Omit<QueuedAward, 'createdAt' | 'attempts' | 'nextAttemptAt'>;

export type EnqueueResult = 'queued' | 'duplicate' | 'full' | 'invalid';

export type QueueEvent =
  | { readonly type: 'confirmed'; readonly item: QueuedAward; readonly replayed: boolean; readonly confirmation: AwardConfirmation }
  | { readonly type: 'rejected'; readonly item: QueuedAward; readonly status: number; readonly code?: string; readonly detail?: string }
  | { readonly type: 'expired'; readonly item: QueuedAward }
  /** Removed without being sent: it belonged to another user's session. */
  | { readonly type: 'discarded'; readonly item: QueuedAward };

export interface FlushSummary {
  readonly confirmed: number;
  readonly rejected: number;
  readonly expired: number;
  /** Items still waiting afterwards. */
  readonly remaining: number;
}

export interface AwardQueueOptions {
  readonly storage: AwardQueueStorage;
  readonly transport: AwardTransport;
  readonly now: () => number;
  /** The signed-in user's id, or null when nobody is (nothing is sent then). */
  readonly getOwnerId: () => string | null;
  readonly limits?: QueueLimits;
}

export type CancelResult = 'cancelled' | 'in-flight' | 'not-found';

/** Some students or the whole class (never both, never neither), points 1 to 10 or the category default. */
function isValid(item: NewAward): boolean {
  if (!item.opId || !item.categoryId || !item.classId || !item.schoolId || !item.ownerId) return false;
  if (item.points !== null && !(Number.isInteger(item.points) && item.points >= 1 && item.points <= 10)) return false;
  if (item.wholeClass) return item.studentIds.length === 0;
  return item.studentIds.length > 0 && item.studentIds.length <= 500 && new Set(item.studentIds).size === item.studentIds.length;
}

interface PassResult {
  confirmed: number;
  rejected: number;
  expired: number;
  stoppedOffline: boolean;
}

export class AwardQueue {
  private items: QueuedAward[] = [];
  private loaded = false;
  private inFlight: string | null = null;
  private running: Promise<FlushSummary> | null = null;
  private again = false;
  private forceAgain = false;
  /** Bumped by `clear()`: results of calls that were in the air at that moment are ignored. */
  private generation = 0;
  private readonly listeners = new Set<() => void>();
  private readonly eventListeners = new Set<(event: QueueEvent) => void>();
  private readonly limits: QueueLimits;

  constructor(private readonly options: AwardQueueOptions) {
    this.limits = options.limits ?? DEFAULT_LIMITS;
  }

  /** Loads the persisted items (once). Safe to call again. */
  async init(): Promise<void> {
    if (this.loaded) return;
    const loaded = await this.options.storage.load();
    if (this.loaded) return; // cleared or initialised while loading
    this.items = loaded;
    this.loaded = true;
    this.emitChange();
  }

  /** A snapshot, oldest first. */
  pending(): readonly QueuedAward[] {
    return this.items;
  }

  count(): number {
    return this.items.length;
  }

  /** Called whenever the items change (a count for the indicator, a list for the optimistic totals). */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onEvent(listener: (event: QueueEvent) => void): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  async enqueue(award: NewAward): Promise<EnqueueResult> {
    await this.init();
    if (!isValid(award)) return 'invalid';
    if (this.items.some((item) => item.opId === award.opId)) return 'duplicate';
    if (this.items.length >= this.limits.maxItems) return 'full';
    const item: QueuedAward = {
      ...award,
      studentIds: [...award.studentIds],
      createdAt: this.options.now(),
      attempts: 0,
      nextAttemptAt: 0,
    };
    await this.options.storage.insert(item);
    this.items = [...this.items, item];
    this.emitChange();
    return 'queued';
  }

  /** Withdraws an award that was not confirmed yet (the teacher tapped "undo" while offline). */
  async cancel(opId: string): Promise<CancelResult> {
    if (this.inFlight === opId) return 'in-flight';
    if (!this.items.some((item) => item.opId === opId)) return 'not-found';
    await this.options.storage.remove(opId);
    this.items = this.items.filter((item) => item.opId !== opId);
    this.emitChange();
    return 'cancelled';
  }

  /** Sign-out: forgets everything, also what is being sent right now. */
  async clear(): Promise<void> {
    this.generation++;
    this.items = [];
    this.loaded = true;
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
    const total = { confirmed: 0, rejected: 0, expired: 0 };
    let forced = force;
    for (;;) {
      this.again = false;
      this.forceAgain = false;
      const result = await this.pass(forced);
      total.confirmed += result.confirmed;
      total.rejected += result.rejected;
      total.expired += result.expired;
      if (result.stoppedOffline || !this.again) break;
      forced = this.forceAgain;
    }
    return { ...total, remaining: this.items.length };
  }

  private async pass(force: boolean): Promise<PassResult> {
    const generation = this.generation;
    const result: PassResult = { confirmed: 0, rejected: 0, expired: 0, stoppedOffline: false };
    const ownerId = this.options.getOwnerId();
    if (ownerId === null) return result;

    for (const item of [...this.items]) {
      if (generation !== this.generation) break;
      if (!this.items.some((current) => current.opId === item.opId)) continue; // cancelled meanwhile
      const now = this.options.now();

      if (item.ownerId !== ownerId) {
        await this.drop(item, { type: 'discarded', item });
        continue;
      }
      if (now - item.createdAt > this.limits.maxAgeMs) {
        await this.drop(item, { type: 'expired', item });
        result.expired++;
        continue;
      }
      if (!force && item.nextAttemptAt > now) continue;

      this.inFlight = item.opId;
      let outcome: SendOutcome;
      try {
        outcome = await this.options.transport(item);
      } catch {
        outcome = { kind: 'retry', reason: 'network' };
      } finally {
        this.inFlight = null;
      }
      if (generation !== this.generation) break; // signed out while the call was in the air

      if (outcome.kind === 'confirmed') {
        await this.drop(item, { type: 'confirmed', item, replayed: outcome.replayed, confirmation: outcome.confirmation });
        result.confirmed++;
      } else if (outcome.kind === 'rejected') {
        await this.drop(item, {
          type: 'rejected',
          item,
          status: outcome.status,
          ...(outcome.code !== undefined ? { code: outcome.code } : {}),
          ...(outcome.detail !== undefined ? { detail: outcome.detail } : {}),
        });
        result.rejected++;
      } else {
        const attempts = item.attempts + 1;
        const wait = Math.max(backoffMs(attempts), outcome.retryAfterMs ?? 0);
        const nextAttemptAt = this.options.now() + wait;
        await this.options.storage.update(item.opId, { attempts, nextAttemptAt });
        this.items = this.items.map((current) => (current.opId === item.opId ? { ...current, attempts, nextAttemptAt } : current));
        this.emitChange();
        if (outcome.reason === 'network') {
          // Nothing reached the server: the next items would fail the same way, and the order stays intact.
          result.stoppedOffline = true;
          break;
        }
      }
    }
    return result;
  }

  private async drop(item: QueuedAward, event: QueueEvent): Promise<void> {
    await this.options.storage.remove(item.opId);
    this.items = this.items.filter((current) => current.opId !== item.opId);
    // Event first, then the change: a screen that turns a confirmed award into a settled one sees both in the
    // same render and the bubble never blinks.
    for (const listener of this.eventListeners) listener(event);
    this.emitChange();
  }

  private emitChange(): void {
    for (const listener of this.listeners) listener();
  }
}
