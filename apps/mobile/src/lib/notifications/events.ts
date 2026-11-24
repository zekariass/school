export interface InboxItem {
  id: string;
  typeCode: string;
  schoolId?: string;
  title: string;
  body?: string;
  data: Record<string, unknown>;
  sourceType?: string;
  sourceId?: string;
  priority: 'NORMAL' | 'URGENT';
  readAt?: string;
  createdAt: string;
}

export type RealtimeEvent =
  | { type: 'notification.created'; notification: InboxItem; unreadCount: number }
  | { type: 'notification.read'; ids: string[]; all: boolean; unreadCount: number }
  | { type: 'notification.archived'; id: string; unreadCount: number };

/** Parses the body of a MESSAGE frame; anything unexpected is ignored (null). */
export function parseRealtimeEvent(body: string): RealtimeEvent | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const e = value as Record<string, unknown>;
  const unread = typeof e.unreadCount === 'number' ? e.unreadCount : null;
  if (unread === null) return null;
  switch (e.type) {
    case 'notification.created': {
      const n = e.notification as Record<string, unknown> | undefined;
      if (!n || typeof n.id !== 'string' || typeof n.title !== 'string' || typeof n.createdAt !== 'string') return null;
      return { type: e.type, notification: n as unknown as InboxItem, unreadCount: unread };
    }
    case 'notification.read': {
      const ids = Array.isArray(e.ids) ? e.ids.filter((i): i is string => typeof i === 'string') : [];
      return { type: e.type, ids, all: e.all === true, unreadCount: unread };
    }
    case 'notification.archived':
      return typeof e.id === 'string' ? { type: e.type, id: e.id, unreadCount: unread } : null;
    default:
      return null;
  }
}
