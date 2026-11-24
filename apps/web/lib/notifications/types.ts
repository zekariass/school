import type { components } from "@school/api-client";

type S = components["schemas"];

export type NotificationItem = S["NotificationView"];
export type NotificationPage = { items: NotificationItem[]; nextCursor?: string };
export type PreferencesView = S["Preferences"];
export type NotificationSettings = S["SettingsResponse"];

/** What the gateway pushes on /user/queue/notifications (frame bodies are JSON text). */
export type RealtimeEvent =
  | { type: "notification.created"; notification: NotificationItem; unreadCount: number }
  | { type: "notification.read"; ids: string[]; all: boolean; unreadCount: number }
  | { type: "notification.archived"; id: string; unreadCount: number };

const isCount = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value) && value >= 0;

/** Parses a frame body; anything unexpected (including probes of other shapes) is null. */
export function parseRealtimeEvent(body: string): RealtimeEvent | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const event = value as Record<string, unknown>;
  switch (event.type) {
    case "notification.created": {
      const n = event.notification as Record<string, unknown> | undefined;
      if (!n || typeof n.id !== "string" || typeof n.title !== "string" || !isCount(event.unreadCount)) {
        return null;
      }
      return {
        type: "notification.created",
        notification: n as unknown as NotificationItem,
        unreadCount: event.unreadCount,
      };
    }
    case "notification.read": {
      if (!isCount(event.unreadCount)) return null;
      const ids = Array.isArray(event.ids) ? event.ids.filter((id): id is string => typeof id === "string") : [];
      return { type: "notification.read", ids, all: event.all === true, unreadCount: event.unreadCount };
    }
    case "notification.archived": {
      if (typeof event.id !== "string" || !isCount(event.unreadCount)) return null;
      return { type: "notification.archived", id: event.id, unreadCount: event.unreadCount };
    }
    default:
      return null;
  }
}
