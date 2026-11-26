import { isUuid } from "@/lib/feed/ids";
import { mk } from "./keys";

/**
 * The real-time "doorbell" on `/user/queue/messages`: something changed in a conversation. It carries ids only, never
 * text or names; the content is always fetched through the API, which applies every rule again. So an event only ever
 * decides what to refetch.
 */
export interface Doorbell {
  type: "message.created" | "message.updated";
  conversationId: string;
  messageId: string;
  at: string | null;
}

/** Parses a frame body; anything that is not a well-formed doorbell (ids must be UUIDs) is ignored. */
export function parseDoorbell(body: string): Doorbell | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const v = value as Record<string, unknown>;
  if (v.type !== "message.created" && v.type !== "message.updated") return null;
  if (!isUuid(v.conversationId) || !isUuid(v.messageId)) return null;
  return { type: v.type, conversationId: v.conversationId, messageId: v.messageId, at: typeof v.at === "string" ? v.at : null };
}

/** The cached queries a doorbell makes stale: the inbox, the header badge, scheduled messages and that conversation. */
export function doorbellKeys(event: Pick<Doorbell, "conversationId">): (readonly unknown[])[] {
  return [mk.inboxAll, mk.unread, mk.scheduled, mk.conversation(event.conversationId), mk.messages(event.conversationId)];
}

/** Everything messaging holds, for a reconnect or a tab that comes back (events missed while offline are not replayed). */
export const ALL_MESSAGING_KEYS: readonly (readonly unknown[])[] = [mk.all];

/** Polling when the socket is not delivering doorbells. A hidden tab does not poll; a healthy socket needs only a slow safety net. */
export function pollInterval(state: { socketUp: boolean; hidden: boolean }): number | false {
  if (state.hidden) return false;
  return state.socketUp ? 120_000 : 15_000;
}
