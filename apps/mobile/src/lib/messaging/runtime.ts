import type { QueryClient } from '@tanstack/react-query';
import { useSyncExternalStore } from 'react';

import { queryClient } from '@/lib/query/query-client';

import { fetchMessage, fetchMessages, markRead, outboxTransport } from './requests';
import { INBOX_PREFIX, MESSAGING_ROOT, afterHoursKey, conversationKey, messagesKey, unreadCountKey } from './keys';
import { clearUnread, isCurrent, mergeLatest, upsertMessage, type MessageData } from './pages';
import { MessageOutbox, type OutboxEntry } from './outbox';
import type { Doorbell } from './doorbell';
import { ApiRequestError } from '@/lib/api-error';

/**
 * The device wiring of the messaging: the one memory-only outbox of the app, and what happens to the caches when the doorbell rings.
 * Nothing here is persisted. `clearMessagingMemory()` runs at sign-out and when the session ends.
 */

export const outbox = new MessageOutbox({
  transport: { send: outboxTransport },
  now: () => Date.now(),
  scheduler: { set: (fn, ms) => setTimeout(fn, ms), clear: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>) },
  onSent: (entry, result) => {
    // The accepted message replaces the "sending" bubble in one step (no gap, no duplicate), then the lists catch up.
    queryClient.setQueryData<MessageData>(messagesKey(entry.conversationId), (current) => (current ? upsertMessage(current, result.message) : undefined));
    if (result.afterHours) queryClient.setQueryData(afterHoursKey(entry.conversationId), Date.now());
    void queryClient.invalidateQueries({ queryKey: INBOX_PREFIX });
  },
});

/** The person has the conversation open and up to date: tell the API (idempotent, never moves back) and drop the unread count at once. */
export async function markConversationRead(client: QueryClient, conversationId: string, upToMessageId?: string): Promise<void> {
  client.setQueriesData<InboxData>({ queryKey: INBOX_PREFIX }, (data) => clearUnread(data, conversationId));
  try {
    await markRead(conversationId, upToMessageId);
  } catch {
    // the next open tries again
  }
  void client.invalidateQueries({ queryKey: unreadCountKey });
}

type InboxData = { pages: { items: { id: string; unreadCount: number }[] }[]; pageParams: unknown[] };

/** The outbox entries (all conversations), re-read when something changes. */
export function useOutboxEntries(): readonly OutboxEntry[] {
  return useSyncExternalStore(outbox.subscribe, outbox.getSnapshot, outbox.getSnapshot);
}

/** Sign-out and end of the session: unsent messages, every cached page, everything about messaging is gone from memory. */
export function clearMessagingMemory(): void {
  outbox.clear();
  queryClient.removeQueries({ queryKey: [MESSAGING_ROOT] });
}

// --- the doorbell ------------------------------------------------------------------------------------------------------

/**
 * A doorbell rang: ids only. The lists refetch (counts and previews come from the API); a conversation whose messages are in
 * memory fetches the one message through the API and puts it in place (so everything the API's rules say applies again). A 403 or
 * 404 means the person lost access meanwhile: what was cached of that conversation is dropped.
 */
export async function handleDoorbell(client: QueryClient, event: Doorbell): Promise<void> {
  void client.invalidateQueries({ queryKey: INBOX_PREFIX });
  void client.invalidateQueries({ queryKey: unreadCountKey });
  const cachedMessages = client.getQueryData<MessageData>(messagesKey(event.conversationId));
  if (!cachedMessages) return;
  void client.invalidateQueries({ queryKey: conversationKey(event.conversationId) });
  try {
    const message = await fetchMessage(event.conversationId, event.messageId);
    if (!isCurrent(client.getQueryData<MessageData>(messagesKey(event.conversationId)), message)) {
      client.setQueryData<MessageData>(messagesKey(event.conversationId), (current) => (current ? upsertMessage(current, message) : undefined));
    }
  } catch (error) {
    if (error instanceof ApiRequestError && (error.status === 403 || error.status === 404)) forgetConversation(client, event.conversationId);
    // anything else: the polling and the next doorbell catch up
  }
}

/** Drops everything cached of one conversation (access lost): the open screen then finds nothing and shows "not available". */
export function forgetConversation(client: QueryClient, conversationId: string): void {
  client.removeQueries({ queryKey: messagesKey(conversationId) });
  client.removeQueries({ queryKey: [MESSAGING_ROOT, 'members', conversationId] });
  void client.invalidateQueries({ queryKey: conversationKey(conversationId) });
  void client.invalidateQueries({ queryKey: INBOX_PREFIX });
  void client.invalidateQueries({ queryKey: unreadCountKey });
}

/** Fetches the newest page of one conversation and folds it into the cache (reconnect, polling, return to the foreground). */
export async function refreshLatest(client: QueryClient, conversationId: string): Promise<void> {
  try {
    const fresh = await fetchMessages(conversationId);
    client.setQueryData<MessageData>(messagesKey(conversationId), (current) => (current ? mergeLatest(current, fresh) : undefined));
  } catch (error) {
    if (error instanceof ApiRequestError && (error.status === 403 || error.status === 404)) forgetConversation(client, conversationId);
  }
}

/** After the socket (re)connected: whatever was missed. Open conversations fold in their newest page; the lists refetch. */
export function catchUpAfterReconnect(client: QueryClient): void {
  void client.invalidateQueries({ queryKey: INBOX_PREFIX });
  void client.invalidateQueries({ queryKey: unreadCountKey });
  for (const query of client.getQueryCache().findAll({ queryKey: [MESSAGING_ROOT, 'messages'] })) {
    const conversationId = query.queryKey[2];
    if (typeof conversationId === 'string' && query.getObserversCount() > 0) void refreshLatest(client, conversationId);
  }
  outbox.kick();
}
