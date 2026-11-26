/**
 * The memory-only outbox of messages that did not reach the server yet.
 *
 * Message text is children's personal data, so, unlike attendance marks (`lib/sync`), nothing here is ever written to SQLite,
 * AsyncStorage or the query cache. A message that cannot be sent (no network, a 5xx, a 429) stays in the conversation as "Not
 * sent: tap to retry" and is retried automatically with backoff, always with the SAME `clientMessageId` (the API stores one message
 * per id, so a repeat of a message that did arrive answers 200 with the original and nothing is sent twice). When the app is
 * closed or the session ends, the unsent text is gone; the conversation screen warns before the person leaves with unsent messages.
 *
 * Pure: the transport, the clock, the timers and the random source are injected, so vitest drives it without a device.
 *
 * Rules:
 * - In a conversation messages go out in the order they were written: while an earlier one waits for a retry, later ones queue
 *   behind it (a 4xx "final" failure does not block them: it will not succeed by waiting). Conversations are independent.
 * - Retryable: no answer, 408, 425, 429 (the `Retry-After` is honoured), 5xx and 401 (the session layer renews or ends the session).
 *   Final: any other 4xx; the API's localized `problem.detail` is kept and shown, and nothing is resent until the person taps.
 * - After `maxAutoRetryMs` without success the automatic retries stop (the person taps to try again).
 */

import type { SendResult } from './types';

/** `sent`: the API accepted it; the entry stays for one tick so the stored message appears before the "sending" bubble goes (no flicker). */
export type OutboxStatus = 'queued' | 'sending' | 'waiting' | 'failed' | 'sent';

export interface SendProblem {
  status?: number | undefined;
  code?: string | undefined;
  title?: string | undefined;
  detail?: string | undefined;
}

export interface OutboxEntry {
  readonly clientMessageId: string;
  readonly conversationId: string;
  /** The text. Memory only. */
  readonly body: string;
  readonly mediaIds: readonly string[];
  readonly replyToMessageId: string | undefined;
  /** When the person pressed send (ms). */
  readonly createdAt: number;
  /** Order of writing, across the whole outbox. */
  readonly seq: number;
  readonly status: OutboxStatus;
  /** Attempts made so far. */
  readonly attempts: number;
  /** When the next automatic attempt is due (status `waiting`). */
  readonly nextAttemptAt: number | undefined;
  /** Why it failed (status `failed`: the API's answer for a final refusal; none when the automatic retries ran out). */
  readonly problem: SendProblem | undefined;
  /** `failed` because the automatic retries ran out, not because the API refused. */
  readonly gaveUp: boolean;
}

export interface EnqueueInput {
  clientMessageId: string;
  conversationId: string;
  body: string;
  mediaIds?: readonly string[];
  replyToMessageId?: string | undefined;
}

export type SendOutcome =
  | { kind: 'sent'; result: SendResult }
  | { kind: 'retry'; retryAfterMs?: number | undefined; problem?: SendProblem | undefined }
  | { kind: 'final'; problem: SendProblem };

export interface OutboxTransport {
  send(entry: OutboxEntry): Promise<SendOutcome>;
}

export interface OutboxScheduler {
  set(fn: () => void, ms: number): unknown;
  clear(handle: unknown): void;
}

export interface OutboxOptions {
  transport: OutboxTransport;
  now: () => number;
  scheduler: OutboxScheduler;
  random?: () => number;
  /** Called once per message the API accepted (201 or 200), with its answer; the entry is already removed. */
  onSent?: (entry: OutboxEntry, result: SendResult) => void;
  maxAutoRetryMs?: number;
}

const BASE_DELAY_MS = 2_000;
const MAX_DELAY_MS = 60_000;
const MAX_RETRY_AFTER_MS = 5 * 60_000;
export const DEFAULT_MAX_AUTO_RETRY_MS = 10 * 60_000;

/** Delay before the automatic attempt number `attempts` + 1: exponential from 2 s to 60 s with equal jitter, never below `Retry-After`. */
export function retryDelayMs(attempts: number, retryAfterMs: number | undefined, random: () => number): number {
  const ceiling = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.max(0, Math.min(attempts - 1, 10)));
  const delay = Math.round(ceiling / 2 + random() * (ceiling / 2));
  if (retryAfterMs === undefined) return delay;
  return Math.max(delay, Math.min(retryAfterMs, MAX_RETRY_AFTER_MS));
}

/**
 * Sorts a failed HTTP call into an outcome. `status` is undefined when there was no answer at all (offline, timeout).
 * A 2xx is handled by the caller (it has the body).
 */
export function classifyFailure(failure: { status?: number | undefined; retryAfterSeconds?: number | undefined; problem?: SendProblem | undefined }): SendOutcome {
  const { status, retryAfterSeconds } = failure;
  const problem: SendProblem = { ...failure.problem, status };
  if (status === undefined || status === 401 || status === 408 || status === 425 || status === 429 || status >= 500) {
    return { kind: 'retry', retryAfterMs: retryAfterSeconds !== undefined ? retryAfterSeconds * 1000 : undefined, problem };
  }
  return { kind: 'final', problem };
}

type Listener = () => void;

export class MessageOutbox {
  private readonly transport: OutboxTransport;
  private readonly now: () => number;
  private readonly scheduler: OutboxScheduler;
  private readonly random: () => number;
  private readonly onSent: ((entry: OutboxEntry, result: SendResult) => void) | undefined;
  private readonly maxAutoRetryMs: number;

  private items: OutboxEntry[] = [];
  private timers = new Map<string, unknown>();
  private listeners = new Set<Listener>();
  private nextSeq = 1;
  /** Bumped by `clear()`: answers of requests started before it are ignored. */
  private generation = 0;
  private snapshotCache: readonly OutboxEntry[] = [];

  constructor(options: OutboxOptions) {
    this.transport = options.transport;
    this.now = options.now;
    this.scheduler = options.scheduler;
    this.random = options.random ?? Math.random;
    this.onSent = options.onSent;
    this.maxAutoRetryMs = options.maxAutoRetryMs ?? DEFAULT_MAX_AUTO_RETRY_MS;
  }

  // --- reading (useSyncExternalStore) -------------------------------------------------------------------------------

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** All entries, oldest first. The same array until something changes. */
  getSnapshot = (): readonly OutboxEntry[] => this.snapshotCache;

  entriesOf(conversationId: string): OutboxEntry[] {
    return this.items.filter((entry) => entry.conversationId === conversationId);
  }

  /** Whether anything of the conversation (or of any conversation) still has to be sent or was not sent. */
  hasUnsent(conversationId?: string): boolean {
    return this.items.some((entry) => entry.status !== 'sent' && (conversationId === undefined || entry.conversationId === conversationId));
  }

  // --- acting -------------------------------------------------------------------------------------------------------

  /** Adds a message and tries to send it at once (after the earlier messages of the same conversation). */
  enqueue(input: EnqueueInput): OutboxEntry {
    if (this.items.some((entry) => entry.clientMessageId === input.clientMessageId)) {
      return this.items.find((entry) => entry.clientMessageId === input.clientMessageId)!;
    }
    const entry: OutboxEntry = {
      clientMessageId: input.clientMessageId,
      conversationId: input.conversationId,
      body: input.body,
      mediaIds: [...(input.mediaIds ?? [])],
      replyToMessageId: input.replyToMessageId,
      createdAt: this.now(),
      seq: this.nextSeq++,
      status: 'queued',
      attempts: 0,
      nextAttemptAt: undefined,
      problem: undefined,
      gaveUp: false,
    };
    this.items = [...this.items, entry];
    this.changed();
    this.pump(entry.conversationId);
    return entry;
  }

  /** The person tapped "Not sent: tap to retry": try again now (the same `clientMessageId`). */
  retryNow(clientMessageId: string): void {
    const entry = this.find(clientMessageId);
    if (!entry || entry.status === 'sending' || entry.status === 'queued' || entry.status === 'sent') return;
    this.cancelTimer(clientMessageId);
    // A failed entry rejoins the line at its original place: earlier messages of the conversation go first.
    this.replace(clientMessageId, { status: 'queued', nextAttemptAt: undefined, problem: undefined, gaveUp: false });
    this.pump(entry.conversationId);
  }

  /** Removes an unsent message (the person gave it up). A request already on its way may still arrive; nothing waits for it. */
  discard(clientMessageId: string): void {
    const entry = this.find(clientMessageId);
    if (!entry) return;
    this.cancelTimer(clientMessageId);
    this.items = this.items.filter((item) => item.clientMessageId !== clientMessageId);
    this.changed();
    this.pump(entry.conversationId);
  }

  /** The network is back or the app returned to the foreground: attempts that were waiting for their timer go now. */
  kick(): void {
    const conversations = new Set(this.items.filter((entry) => entry.status === 'waiting').map((entry) => entry.conversationId));
    for (const entry of this.items) {
      if (entry.status === 'waiting') {
        this.cancelTimer(entry.clientMessageId);
        this.replace(entry.clientMessageId, { status: 'queued', nextAttemptAt: undefined });
      }
    }
    for (const conversationId of conversations) this.pump(conversationId);
  }

  /** Sign-out or end of the session: nothing survives, nothing is sent any more, answers still on their way are ignored. */
  clear(): void {
    this.generation += 1;
    for (const handle of this.timers.values()) this.scheduler.clear(handle);
    this.timers.clear();
    this.items = [];
    this.nextSeq = 1;
    this.changed();
  }

  // --- internals ----------------------------------------------------------------------------------------------------

  private find(clientMessageId: string): OutboxEntry | undefined {
    return this.items.find((entry) => entry.clientMessageId === clientMessageId);
  }

  private replace(clientMessageId: string, patch: Partial<Omit<OutboxEntry, 'clientMessageId' | 'conversationId' | 'seq'>>): void {
    this.items = this.items.map((entry) => (entry.clientMessageId === clientMessageId ? { ...entry, ...patch } : entry));
    this.changed();
  }

  private changed(): void {
    this.snapshotCache = this.items;
    for (const listener of [...this.listeners]) listener();
  }

  private cancelTimer(clientMessageId: string): void {
    const handle = this.timers.get(clientMessageId);
    if (handle !== undefined) {
      this.scheduler.clear(handle);
      this.timers.delete(clientMessageId);
    }
  }

  /** Starts, or schedules, the next attempt of a conversation: only the oldest live entry goes, so the order is kept. */
  private pump(conversationId: string): void {
    const line = this.items.filter((entry) => entry.conversationId === conversationId && entry.status !== 'failed' && entry.status !== 'sent');
    const head = line[0];
    if (!head || head.status === 'sending') return;
    if (head.status === 'waiting') {
      if (this.timers.has(head.clientMessageId)) return;
      const due = head.nextAttemptAt ?? this.now();
      this.timers.set(
        head.clientMessageId,
        this.scheduler.set(() => {
          this.timers.delete(head.clientMessageId);
          this.replace(head.clientMessageId, { status: 'queued', nextAttemptAt: undefined });
          this.pump(conversationId);
        }, Math.max(0, due - this.now())),
      );
      return;
    }
    void this.attempt(head);
  }

  private async attempt(entry: OutboxEntry): Promise<void> {
    const generation = this.generation;
    const attempting: OutboxEntry = { ...entry, status: 'sending', attempts: entry.attempts + 1 };
    this.replace(entry.clientMessageId, { status: 'sending', attempts: attempting.attempts });
    let outcome: SendOutcome;
    try {
      outcome = await this.transport.send(attempting);
    } catch {
      outcome = { kind: 'retry' };
    }
    if (generation !== this.generation) return; // signed out meanwhile
    const current = this.find(entry.clientMessageId);
    if (!current) return; // discarded while on its way

    if (outcome.kind === 'sent') {
      // The cache gets the stored message first; the "sending" bubble goes one tick later, so the list never shows a gap.
      this.replace(entry.clientMessageId, { status: 'sent', nextAttemptAt: undefined, problem: undefined });
      this.onSent?.(current, outcome.result);
      this.timers.set(
        entry.clientMessageId,
        this.scheduler.set(() => {
          this.timers.delete(entry.clientMessageId);
          this.items = this.items.filter((item) => item.clientMessageId !== entry.clientMessageId);
          this.changed();
        }, 0),
      );
    } else if (outcome.kind === 'final') {
      this.replace(entry.clientMessageId, { status: 'failed', problem: outcome.problem, nextAttemptAt: undefined, gaveUp: false });
    } else if (this.now() - current.createdAt >= this.maxAutoRetryMs) {
      this.replace(entry.clientMessageId, { status: 'failed', problem: outcome.problem, nextAttemptAt: undefined, gaveUp: true });
    } else {
      const delay = retryDelayMs(current.attempts, outcome.retryAfterMs, this.random);
      this.replace(entry.clientMessageId, { status: 'waiting', nextAttemptAt: this.now() + delay, problem: outcome.problem });
    }
    this.pump(entry.conversationId);
  }
}
