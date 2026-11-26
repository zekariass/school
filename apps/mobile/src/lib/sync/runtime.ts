import * as Network from 'expo-network';
import { useEffect, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';

import { api } from '@/lib/api';
import { parseRetryAfter } from '@/lib/api-error';
import { patchRollFromResult } from '@/lib/attendance/roll-sync';
import { getDatabase } from '@/lib/db/database';
import { getDeviceId } from '@/lib/db/device-id';
import { offlineCache, setOfflineOwner } from '@/lib/offline/runtime';
import { OFFLINE_IDENTITY_KEY } from '@/lib/session/offline-identity';
import { kv } from '@/lib/storage';

import { SyncEngine } from './engine';
import { sqlOutboxStorage } from './outbox-sql';
import { classifyRequestFailure, readBatchResponse, toRequestOperation } from './protocol';
import type { BatchOutcome, QueuedAttendance, QueuedOperation, SyncEvent } from './types';

/**
 * The device side of the outbox: SQLite storage, `POST /sync` as transport, and the moments to try again (start,
 * reconnect, back in the foreground, a timer). The rules live in `engine.ts`; the wire format in `protocol.ts`.
 */

let ownerId: string | null = null;

function problemFields(error: unknown): { code?: string | undefined; detail?: string | undefined } | undefined {
  if (typeof error !== 'object' || error === null) return undefined;
  const record = error as Record<string, unknown>;
  return {
    code: typeof record.code === 'string' ? record.code : undefined,
    detail: typeof record.detail === 'string' ? record.detail : undefined,
  };
}

async function send(batch: readonly QueuedOperation[]): Promise<BatchOutcome> {
  try {
    const state = await Network.getNetworkStateAsync();
    // The device knows it is offline: do not even try (and keep the operations editable).
    if (state.isConnected === false) return { kind: 'retry', reason: 'offline' };
  } catch {
    // Unknown: try.
  }
  try {
    const deviceId = await getDeviceId();
    // No Idempotency-Key header: the operation id is the idempotency key, and it outlives that header's 24 hours.
    const result = await api.POST('/api/v1/sync', {
      body: { operations: batch.map((op) => toRequestOperation(op, deviceId) as never) },
    });
    if (result.response.status === 200 && result.data) return readBatchResponse(result.data);
    return classifyRequestFailure(result.response.status, problemFields(result.error), parseRetryAfter(result.response.headers?.get('Retry-After')));
  } catch {
    return { kind: 'retry', reason: 'network' };
  }
}

export const syncEngine = new SyncEngine({
  storage: sqlOutboxStorage(getDatabase),
  transport: send,
  now: () => Date.now(),
  getOwnerId: () => ownerId,
});

// What the server decided about an attendance roll goes straight into the screen's roll and the offline copy.
syncEngine.onEvent((event: SyncEvent) => {
  if ((event.type === 'applied' || event.type === 'conflict') && event.item.type === 'ATTENDANCE_MARK') {
    void patchRollFromResult(event.item as QueuedAttendance, event.state);
  }
});

let flushTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * Sends soon, but not at once: a teacher tapping through a class makes one request, not thirty (the taps are merged
 * into one operation while it waits).
 */
export function requestFlush(delayMs = 700): void {
  if (flushTimer) clearTimeout(flushTimer);
  flushTimer = setTimeout(() => {
    flushTimer = null;
    void syncEngine.flush(true);
  }, delayMs);
}

/**
 * Sign-out or the end of a session: nothing of the outbox or the offline cache survives (operations, conflicts,
 * rejections, cached rosters, rolls, totals, memory-only notes), nor the remembered profile that lets a teacher
 * open the app offline.
 */
export async function wipeOfflineData(): Promise<void> {
  ownerId = null;
  if (flushTimer) clearTimeout(flushTimer);
  flushTimer = null;
  setOfflineOwner(null);
  const results = await Promise.allSettled([syncEngine.clear(), offlineCache.clear(), kv.removeItem(OFFLINE_IDENTITY_KEY)]);
  for (const result of results) {
    // Nothing is sent without an owner and another user's rows are discarded unsent, so a failed wipe cannot
    // act under another account; it is retried at the next sign-out.
    if (result.status === 'rejected' && __DEV__) console.warn('[sync] could not wipe offline data', result.reason);
  }
}

const RETRY_INTERVAL_MS = 15_000;

/**
 * Starts the outbox for the signed-in teacher: now, on reconnect, when the app returns to the foreground and every
 * 15 s while something waits (the engine applies its own backoff). Returns the stop function.
 */
export function startSyncRuntime(owner: string): () => void {
  ownerId = owner;
  setOfflineOwner(owner);
  void offlineCache.tidy().catch(() => {});
  void syncEngine.init().then(() => syncEngine.flush(true));

  const network = Network.addNetworkStateListener((state) => {
    if (state.isConnected !== false) void syncEngine.flush(true);
  });
  const appState = AppState.addEventListener('change', (status) => {
    if (status === 'active') void syncEngine.flush(true);
  });
  const timer = setInterval(() => {
    if (syncEngine.count() > 0) void syncEngine.flush(false);
  }, RETRY_INTERVAL_MS);

  return () => {
    network.remove();
    appState.remove();
    clearInterval(timer);
    if (ownerId === owner) {
      ownerId = null;
      setOfflineOwner(null);
    }
  };
}

/** The operations waiting to be sent (re-renders when it changes). */
export function usePendingOperations(): readonly QueuedOperation[] {
  return useSyncExternalStore(
    (listener) => syncEngine.subscribe(listener),
    () => syncEngine.pending(),
    () => syncEngine.pending(),
  );
}

/** Conflicts, rejections and expired operations the teacher still has to look at. */
export function useAttentionOperations(): readonly QueuedOperation[] {
  return useSyncExternalStore(
    (listener) => syncEngine.subscribe(listener),
    () => syncEngine.attention(),
    () => syncEngine.attention(),
  );
}

/** Calls `listener` for every outbox event (applied, conflict, rejected, expired) while mounted. */
export function useSyncEvents(listener: (event: SyncEvent) => void): void {
  useEffect(() => syncEngine.onEvent(listener), [listener]);
}
