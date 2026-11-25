import * as Network from 'expo-network';
import { useEffect, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';

import { api } from '@/lib/api';
import { getDatabase } from '@/lib/db/database';

import { AwardQueue, type QueueEvent, type QueuedAward, type SendOutcome } from './award-queue';
import { outcomeFromResult } from './award-outcome';
import { sqlAwardQueueStorage } from './award-queue-sql';

/**
 * The device side of the award queue: SQLite storage, the API as transport, and the moments to try again
 * (reconnect, back in the foreground, a timer). The rules live in `award-queue.ts`.
 */

let ownerId: string | null = null;

async function send(item: QueuedAward): Promise<SendOutcome> {
  try {
    // No Idempotency-Key header: the op id is the idempotency key, and it outlives that header's 24 hours.
    const result = await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/points/awards', {
      params: { path: { schoolId: item.schoolId, classId: item.classId } },
      body: {
        categoryId: item.categoryId,
        clientOpId: item.opId,
        ...(item.wholeClass ? { wholeClass: true } : { studentIds: [...item.studentIds] }),
        ...(item.points !== null ? { points: item.points } : {}),
      },
    });
    return outcomeFromResult(result);
  } catch {
    return { kind: 'retry', reason: 'network' };
  }
}

export const awardQueue = new AwardQueue({
  storage: sqlAwardQueueStorage(getDatabase),
  transport: send,
  now: () => Date.now(),
  getOwnerId: () => ownerId,
});

// What went wrong for good (in memory only; the queue itself holds no names).
export interface AwardFailure {
  readonly id: string;
  readonly kind: 'rejected' | 'expired';
  readonly item: QueuedAward;
  /** The API's localized detail for a rejection. */
  readonly detail: string | undefined;
}

let failures: readonly AwardFailure[] = [];
const failureListeners = new Set<() => void>();

function setFailures(next: readonly AwardFailure[]) {
  failures = next;
  for (const listener of failureListeners) listener();
}

awardQueue.onEvent((event: QueueEvent) => {
  if (event.type === 'rejected') {
    setFailures([...failures, { id: event.item.opId, kind: 'rejected', item: event.item, detail: event.detail }]);
  } else if (event.type === 'expired') {
    setFailures([...failures, { id: event.item.opId, kind: 'expired', item: event.item, detail: undefined }]);
  }
});

export function dismissAwardFailure(id: string): void {
  setFailures(failures.filter((failure) => failure.id !== id));
}

export function dismissAllAwardFailures(): void {
  setFailures([]);
}

/** Sign-out: nothing of the queue survives (items, failures, optimistic state). */
export async function clearAwardQueue(): Promise<void> {
  ownerId = null;
  setFailures([]);
  try {
    await awardQueue.clear();
  } catch (error) {
    // Nothing is sent without an owner and a different user's items are discarded unsent, so a failed wipe
    // cannot award under another account; it is retried at the next sign-out.
    if (__DEV__) console.warn('[points] could not clear the award queue', error);
  }
}

const RETRY_INTERVAL_MS = 15_000;

/**
 * Starts sending for the signed-in teacher: now, on reconnect, when the app returns to the foreground and every
 * 15 s while something waits (the queue applies its own backoff). Returns the stop function.
 */
export function startAwardRuntime(owner: string): () => void {
  ownerId = owner;
  void awardQueue.init().then(() => awardQueue.flush(true));

  const network = Network.addNetworkStateListener((state) => {
    if (state.isConnected !== false) void awardQueue.flush(true);
  });
  const appState = AppState.addEventListener('change', (status) => {
    if (status === 'active') void awardQueue.flush(true);
  });
  const timer = setInterval(() => {
    if (awardQueue.count() > 0) void awardQueue.flush(false);
  }, RETRY_INTERVAL_MS);

  return () => {
    network.remove();
    appState.remove();
    clearInterval(timer);
    if (ownerId === owner) ownerId = null;
  };
}

/** The awards waiting to be sent (re-renders when it changes). */
export function useQueuedAwards(): readonly QueuedAward[] {
  return useSyncExternalStore(
    (listener) => awardQueue.subscribe(listener),
    () => awardQueue.pending(),
    () => awardQueue.pending(),
  );
}

export function useAwardFailures(): readonly AwardFailure[] {
  return useSyncExternalStore(
    (listener) => {
      failureListeners.add(listener);
      return () => failureListeners.delete(listener);
    },
    () => failures,
    () => failures,
  );
}

/** Calls `listener` for every queue event (confirmed awards, rejections) while mounted. */
export function useAwardQueueEvents(listener: (event: QueueEvent) => void): void {
  useEffect(() => awardQueue.onEvent(listener), [listener]);
}
