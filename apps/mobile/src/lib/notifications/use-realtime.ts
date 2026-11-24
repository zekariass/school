import { Client } from '@stomp/stompjs';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { AppState } from 'react-native';

import { API_BASE_URL } from '@/lib/http';
import { getValidAccessToken, refreshTokens } from '@/lib/auth/session-auth';

import { reduceEvent, type InboxData } from './cache';
import { reconnectDelayMs } from './backoff';
import { parseRealtimeEvent } from './events';
import { INBOX_PREFIX, inboxKey, unreadCountKey } from './keys';
import { realtimeUrl } from './realtime-url';

const QUEUE = '/user/queue/notifications';
const HEARTBEAT_MS = 10_000;
/** Application close code of the gateway: the session was revoked or the account deactivated. */
const SESSION_ENDED_CLOSE = 4401;

/**
 * Keeps one STOMP-over-WebSocket connection to the API while `enabled` and the app is in the
 * foreground, and applies the pushed events to the query cache (inbox lists and unread count).
 *
 * - Authenticates with `Authorization: Bearer <access token>`, read through the same single-flight
 *   path as HTTP calls just before each connect, so a reconnect always carries a fresh token.
 * - Reconnects with jittered exponential backoff; coming back to the foreground connects at
 *   once. After a refusal (`auth.*` ERROR frame or close 4401) it renews the session tokens
 *   first: if the session really ended, the session layer signs the user out.
 * - Events missed while disconnected are recovered by refetching the inbox and the count after
 *   each (re)connect.
 */
export function useRealtime(enabled: boolean): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    let client: Client | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;
    let connecting = false;

    const apply = (body: string) => {
      const event = parseRealtimeEvent(body);
      if (!event) return;
      const update = reduceEvent(event, new Date().toISOString());
      queryClient.setQueryData<InboxData>(inboxKey(false), update.inbox);
      queryClient.setQueryData<InboxData>(inboxKey(true), update.unreadOnly);
      queryClient.setQueryData<number>(unreadCountKey, update.unreadCount);
    };

    const teardown = () => {
      if (timer) clearTimeout(timer);
      timer = null;
      const old = client;
      client = null;
      if (old) {
        old.onWebSocketClose = () => undefined;
        old.onStompError = () => undefined;
        void old.deactivate({ force: true });
      }
    };

    const scheduleReconnect = (renewSession: boolean) => {
      if (stopped || timer) return;
      const delay = reconnectDelayMs(attempt++);
      timer = setTimeout(async () => {
        timer = null;
        if (renewSession) await refreshTokens();
        void connect();
      }, delay);
    };

    const connect = async () => {
      if (stopped || connecting || client?.connected) return;
      connecting = true;
      try {
        const token = await getValidAccessToken();
        if (stopped) return;
        if (!token) return; // signed out meanwhile
        const next = new Client({
          brokerURL: realtimeUrl(API_BASE_URL),
          connectHeaders: { Authorization: `Bearer ${token}` },
          heartbeatIncoming: HEARTBEAT_MS,
          heartbeatOutgoing: HEARTBEAT_MS,
          reconnectDelay: 0, // we do our own, with a fresh token each time
          // React Native's WebSocket needs these (stompjs docs, "React Native").
          forceBinaryWSFrames: true,
          appendMissingNULLonIncoming: true,
          onConnect: () => {
            attempt = 0;
            next.subscribe(QUEUE, (message) => apply(message.body));
            // anything that happened while we were away
            void queryClient.invalidateQueries({ queryKey: INBOX_PREFIX });
            void queryClient.invalidateQueries({ queryKey: unreadCountKey });
          },
          onStompError: (frame) => {
            const reason = frame.headers.message ?? '';
            if (__DEV__) console.log(`[realtime] refused: ${reason}`);
            scheduleReconnect(reason.startsWith('auth.'));
          },
          onWebSocketClose: (event: { code?: number }) => {
            if (client !== next) return;
            client = null;
            scheduleReconnect(event?.code === SESSION_ENDED_CLOSE);
          },
          onWebSocketError: () => {
            // onWebSocketClose follows
          },
        });
        client = next;
        next.activate();
      } finally {
        connecting = false;
      }
    };

    void connect();

    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        attempt = 0;
        if (timer) clearTimeout(timer);
        timer = null;
        void connect();
      } else {
        // a backgrounded socket is killed by the OS anyway; reconnect on return
        teardown();
      }
    });

    return () => {
      stopped = true;
      subscription.remove();
      teardown();
    };
  }, [enabled, queryClient]);
}
