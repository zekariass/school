/** The notification types of the calendar (all point at `/calendar/events/{eventId}`). */
export const CALENDAR_NOTIFICATION_TYPES = ['EVENT_INVITATION', 'EVENT_UPDATED', 'EVENT_CANCELLED', 'UPCOMING_EVENT'] as const;

export function isCalendarNotification(item: { typeCode?: unknown; data?: unknown }): boolean {
  if (typeof item.typeCode === 'string' && (CALENDAR_NOTIFICATION_TYPES as readonly string[]).includes(item.typeCode)) return true;
  const path = (item.data as { path?: unknown } | undefined)?.path;
  return typeof path === 'string' && path.startsWith('/calendar/events/');
}
