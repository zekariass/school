/** The notification types of the communications centre (all point at `/announcements/{id}`). */
export const ANNOUNCEMENT_NOTIFICATION_TYPES = [
  'ANNOUNCEMENT_PUBLISHED',
  'ACK_REQUESTED',
  'ANNOUNCEMENT_UPDATED',
  'ANNOUNCEMENT_REMINDER',
  'ANNOUNCEMENT_WITHDRAWN',
  'ANNOUNCEMENT_PUBLISH_BLOCKED',
] as const;

export function isAnnouncementNotification(item: { typeCode?: unknown; data?: unknown }): boolean {
  if (typeof item.typeCode === 'string' && (ANNOUNCEMENT_NOTIFICATION_TYPES as readonly string[]).includes(item.typeCode)) return true;
  const path = (item.data as { path?: unknown } | undefined)?.path;
  return typeof path === 'string' && (path === '/announcements' || path.startsWith('/announcements/'));
}

/**
 * Whether a notification's data says it is an URGENT or SAFETY announcement (`announcementType`, sent with every announcement
 * notification and push). The foreground handler then plays the notification sound even though other pushes are silent.
 */
export function isUrgentAnnouncementData(data: unknown): boolean {
  const record = typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : null;
  const nested = typeof record?.body === 'object' && record.body !== null ? (record.body as Record<string, unknown>) : null;
  const type = record?.announcementType ?? nested?.announcementType;
  return type === 'URGENT' || type === 'SAFETY';
}

/**
 * The unread notifications that belong to an announcement (`data.announcementId`: published, reminder, update, acknowledgement
 * request...). Opening the announcement reads it on the server, but that does not touch its notifications, so the screen marks
 * these read too (otherwise the bell keeps counting what the person has already read).
 */
export function notificationIdsFor(items: readonly { id: string; readAt?: string | undefined; data?: unknown }[], announcementId: string): string[] {
  return items
    .filter((item) => !item.readAt && (item.data as { announcementId?: unknown } | undefined)?.announcementId === announcementId)
    .map((item) => item.id);
}
