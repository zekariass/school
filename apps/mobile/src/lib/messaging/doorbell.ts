/**
 * The realtime "doorbell" of the messaging (`/user/queue/messages`): `{type, conversationId, messageId, at}`, ids only. It never
 * carries content; the app asks the API for the message, which applies every rule again. Anything unexpected is ignored.
 */

export type Doorbell = { type: 'message.created' | 'message.updated'; conversationId: string; messageId: string; at: string | undefined };

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export function parseDoorbell(body: string): Doorbell | null {
  let value: unknown;
  try {
    value = JSON.parse(body);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const e = value as Record<string, unknown>;
  if (e.type !== 'message.created' && e.type !== 'message.updated') return null;
  if (typeof e.conversationId !== 'string' || !UUID.test(e.conversationId)) return null;
  if (typeof e.messageId !== 'string' || !UUID.test(e.messageId)) return null;
  return { type: e.type, conversationId: e.conversationId, messageId: e.messageId, at: typeof e.at === 'string' ? e.at : undefined };
}

/** How often to ask the API again while the socket is down (ms), or false while it is up. The inbox is polled slower than an open conversation. */
export function pollIntervalMs(connected: boolean, scope: 'conversation' | 'inbox' | 'badge'): number | false {
  if (connected) return false;
  return scope === 'conversation' ? 8_000 : scope === 'inbox' ? 25_000 : 45_000;
}
