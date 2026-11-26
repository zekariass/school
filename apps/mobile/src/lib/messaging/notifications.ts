/**
 * Messaging notifications. The push carries no message text, no child's name and no full name: its `data.path` is `/messages/{id}`
 * (`NEW_MESSAGE`, `NEW_MESSAGE_QUIET`, `MESSAGE_OBSERVER_ADDED`, `MESSAGE_REPORTED`, `MESSAGE_SCHEDULE_BLOCKED`) or `/messages/groups`
 * (`GROUP_APPROVAL_REQUESTED`). Pure: the path mapper (`lib/notifications/routes.ts`) and the foreground handler use these.
 */

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
export const CONVERSATION_PATH = new RegExp(`^/messages/(${UUID})$`);
export const GROUPS_PATH = '/messages/groups';

/** The conversation a path opens, or null. */
export function conversationIdFromPath(path: unknown): string | null {
  if (typeof path !== 'string') return null;
  return CONVERSATION_PATH.exec(path)?.[1] ?? null;
}

/** Notification types of the messaging (the foreground banner keeps them quiet while that conversation is open). */
export const MESSAGE_NOTIFICATION_TYPES = ['NEW_MESSAGE', 'NEW_MESSAGE_QUIET', 'MESSAGE_OBSERVER_ADDED', 'MESSAGE_REPORTED', 'GROUP_APPROVAL_REQUESTED', 'MESSAGE_SCHEDULE_BLOCKED'] as const;

/**
 * Whether a pushed notification that arrives while the app is open should be left out of the banner: it is a message notification
 * for the conversation that is on screen right now.
 */
export function isForOpenConversation(data: unknown, openConversationId: string | null): boolean {
  if (!openConversationId || typeof data !== 'object' || data === null) return false;
  const record = data as Record<string, unknown>;
  const nested = typeof record.body === 'object' && record.body !== null ? (record.body as Record<string, unknown>) : undefined;
  const path = record.path ?? nested?.path;
  return conversationIdFromPath(path)?.toLowerCase() === openConversationId.toLowerCase();
}
