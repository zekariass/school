/** Query keys of the notification data (all under one root so they can be handled together). */
export const NOTIFICATIONS_ROOT = 'notifications';

export const inboxKey = (unreadOnly = false) => [NOTIFICATIONS_ROOT, 'inbox', { unreadOnly }] as const;
export const INBOX_PREFIX = [NOTIFICATIONS_ROOT, 'inbox'] as const;
export const unreadCountKey = [NOTIFICATIONS_ROOT, 'unread-count'] as const;
export const preferencesKey = [NOTIFICATIONS_ROOT, 'preferences'] as const;
export const settingsKey = [NOTIFICATIONS_ROOT, 'settings'] as const;

/**
 * Notification queries are not written to the persisted cache: titles and bodies describe
 * children's school life, the inbox is cheap to refetch and the unread badge must never be a
 * day-old value restored at launch. (Sign-out also clears the whole cache.)
 */
export function isPersistableNotificationQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== NOTIFICATIONS_ROOT;
}
