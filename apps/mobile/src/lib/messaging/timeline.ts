import type { OutboxEntry } from './outbox';
import type { MessageItem } from './types';

export type TimelineItem = { kind: 'message'; key: string; message: MessageItem } | { kind: 'pending'; key: string; entry: OutboxEntry };

/**
 * What the (inverted) list shows, newest first: the messages that have not reached the server yet (the outbox, newest first), then
 * the stored ones. A pending message is always the newest: it was written after everything the server has.
 */
export function buildTimeline(stored: readonly MessageItem[], pending: readonly OutboxEntry[]): TimelineItem[] {
  const unsent = [...pending].sort((a, b) => b.seq - a.seq).map((entry): TimelineItem => ({ kind: 'pending', key: `pending-${entry.clientMessageId}`, entry }));
  return [...unsent, ...stored.map((message): TimelineItem => ({ kind: 'message', key: message.id, message }))];
}
