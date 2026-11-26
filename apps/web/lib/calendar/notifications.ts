/**
 * True for a notification about the calendar (`EVENT_INVITATION`, `EVENT_UPDATED`, `EVENT_CANCELLED`, `UPCOMING_EVENT`):
 * its `data.path` is `/calendar/events/{eventId}`. A live one refreshes the calendar windows and events that are open.
 */
export function isCalendarNotification(data: unknown): boolean {
  if (typeof data !== "object" || data === null) return false;
  const path = (data as { path?: unknown }).path;
  return typeof path === "string" && (path === "/calendar" || path.startsWith("/calendar/events/"));
}
