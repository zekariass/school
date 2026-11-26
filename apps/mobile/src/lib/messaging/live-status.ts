/**
 * Small in-memory stores for the live side of the messaging (no persistence, no imports):
 * - whether the realtime socket is connected right now (the screens poll only while the doorbell cannot ring; `useRealtime` writes it);
 * - which conversation is open on screen (a foreground push for it is not shown: the person is already looking).
 */
let connected = false;
const listeners = new Set<() => void>();

export const liveStatus = {
  get: () => connected,
  set(value: boolean) {
    if (connected === value) return;
    connected = value;
    for (const listener of [...listeners]) listener();
  },
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};

let openConversationId: string | null = null;

export const openConversation = {
  get: () => openConversationId,
  /** Marks a conversation as open; returns the function that closes it (only if it is still the open one). */
  open(id: string): () => void {
    openConversationId = id;
    return () => {
      if (openConversationId === id) openConversationId = null;
    };
  },
};
