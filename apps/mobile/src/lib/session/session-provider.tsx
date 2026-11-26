import { useQueryClient } from '@tanstack/react-query';
import * as Network from 'expo-network';
import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, useRef, type ReactNode } from 'react';
import { AppState } from 'react-native';

import { ApiRequestError } from '@/lib/api-error';
import { loadMe, logout } from '@/lib/auth/auth-requests';
import type { IssuedTokens } from '@/lib/auth/refresh';
import { refreshTokens, sessionEnded } from '@/lib/auth/session-auth';
import { ME_KEY } from '@/lib/auth/use-me';
import { unregisterStoredPushDevice } from '@/lib/notifications/device';
import { endReasonFor } from '@/lib/auth/session-events';
import { clearMessagingMemory } from '@/lib/messaging/runtime';
import { clearQueryCache } from '@/lib/query/query-client';
import { kv, saveQuietly } from '@/lib/storage';
import { wipeOfflineData } from '@/lib/sync/runtime';

import { resolveSessionFromMe, type MeSummary } from './me-roles';
import { OFFLINE_IDENTITY_KEY, parseOfflineIdentity, toOfflineIdentity, type OfflineIdentity } from './offline-identity';
import { ROLES, isRole, type Role } from './roles';
import {
  ACTIVE_ROLE_STORAGE_KEY,
  SIGN_IN_METHOD_STORAGE_KEY,
  isSignInMethod,
  type SignInMethod,
} from './sign-in-method';
import { INITIAL_SESSION, sessionReducer, type SessionState } from './state';
import { clearTokens, readRefreshToken, storeTokens } from './tokens';

export interface SessionValue extends SessionState {
  setActiveRole: (role: Role) => void;
  /**
   * Finishes sign-in with a freshly issued token pair: stores it, loads the profile and
   * signs the user in. Rejects (after forgetting the tokens) if the profile cannot be loaded.
   */
  completeSignIn: (tokens: IssuedTokens, method?: SignInMethod) => Promise<void>;
  /** After `status === 'unreachable'`: tries to restore the stored session again. */
  retryRestore: () => Promise<void>;
  /** Hides the "your session ended" notice. */
  dismissEndReason: () => void;
  /** Revokes the session on the server (best effort), clears tokens, the session and cached server data. */
  signOut: () => Promise<void>;
  /** DEVELOPMENT ONLY: in-memory session with every role, no tokens. No-op in release builds. */
  startPreview: (role: Role) => void;
  /** A teacher opened the app with no connection: the session is the last one the server confirmed, not yet renewed. */
  offlineSession: boolean;
}

const SessionContext = createContext<SessionValue | null>(null);

/** The remembered sign-in method and role (plain preferences, never secrets). Failures read as "unknown". */
async function readRemembered(): Promise<{ method: SignInMethod | null; role: Role | null }> {
  try {
    const [method, role] = await Promise.all([
      kv.getItem(SIGN_IN_METHOD_STORAGE_KEY),
      kv.getItem(ACTIVE_ROLE_STORAGE_KEY),
    ]);
    return { method: isSignInMethod(method) ? method : null, role: isRole(role) ? role : null };
  } catch {
    return { method: null, role: null };
  }
}

async function readOfflineIdentity(): Promise<OfflineIdentity | null> {
  try {
    return parseOfflineIdentity(await kv.getItem(OFFLINE_IDENTITY_KEY), Date.now());
  } catch {
    return null;
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(sessionReducer, INITIAL_SESSION);
  const queryClient = useQueryClient();
  const previewRef = useRef(false);
  useEffect(() => {
    previewRef.current = state.preview;
  }, [state.preview]);
  /** Only the newest restore may change the session. */
  const restoreRun = useRef(0);
  const offlineSession = state.offline;

  const invalidateRestore = useCallback(() => {
    restoreRun.current++;
  }, []);

  const establish = useCallback(
    (
      me: MeSummary & { id?: string },
      remembered: { method: SignInMethod | null; role: Role | null },
      type: 'signedIn' | 'restored' = 'signedIn',
    ) => {
      const identity = resolveSessionFromMe(me, remembered.role);
      // The profile screens read the same data as the settings avatar section.
      queryClient.setQueryData(['me'], me);
      // A teacher may open the app offline next time: remember who they are (see offline-identity.ts).
      const offline = toOfflineIdentity(me, Date.now());
      if (offline) saveQuietly(OFFLINE_IDENTITY_KEY, JSON.stringify(offline));
      else void kv.removeItem(OFFLINE_IDENTITY_KEY).catch(() => {});
      dispatch({
        type,
        roles: identity.roles,
        preferredRole: remembered.role,
        signInMethod: remembered.method,
        profile: { displayName: me.displayName, accessKind: identity.accessKind },
      });
    },
    [queryClient],
  );

  const establishOffline = useCallback(
    (offline: OfflineIdentity, remembered: { method: SignInMethod | null; role: Role | null }) => {
      const identity = resolveSessionFromMe(offline.me, remembered.role);
      // The profile query has the full profile when it is still cached; otherwise it starts from this one.
      queryClient.setQueryData(ME_KEY, (old: unknown) => old ?? { ...offline.me, id: offline.userId });
      dispatch({
        type: 'restored',
        offline: true,
        roles: identity.roles,
        preferredRole: remembered.role,
        signInMethod: remembered.method,
        profile: { displayName: offline.me.displayName, accessKind: identity.accessKind },
      });
    },
    [queryClient],
  );

  const restore = useCallback(async () => {
    const run = ++restoreRun.current;
    const current = () => run === restoreRun.current;

    const remembered = await readRemembered();
    const outcome = await refreshTokens();
    if (!current()) return;
    switch (outcome.status) {
      case 'no-token':
        dispatch({ type: 'signedOut', signInMethod: remembered.method });
        return;
      case 'ended':
        dispatch({ type: 'signedOut', reason: endReasonFor(outcome.code), signInMethod: remembered.method });
        return;
      case 'unavailable': {
        // No connection: a teacher still works from the phone's own copy; anyone else sees the offline screen.
        const offline = await readOfflineIdentity();
        if (!current()) return;
        if (offline) establishOffline(offline, remembered);
        else dispatch({ type: 'unreachable' });
        return;
      }
      case 'refreshed':
        break;
    }
    try {
      const me = await loadMe();
      if (current()) establish(me, remembered, 'restored');
    } catch (error) {
      if (!current()) return;
      if (error instanceof ApiRequestError && error.status === 401) {
        await clearTokens();
        dispatch({ type: 'signedOut', reason: 'expired', signInMethod: remembered.method });
      } else {
        // Offline, timeout or a server error: the tokens are fine, we just cannot see the profile yet.
        dispatch({ type: 'unreachable' });
      }
    }
  }, [establish, establishOffline]);

  // The API layer found the session over (refresh refused, revoked, account inactive).
  useEffect(
    () =>
      sessionEnded.subscribe((reason) => {
        if (previewRef.current) return;
        dispatch({ type: 'signedOut', reason });
        clearMessagingMemory();
        void clearQueryCache();
        void wipeOfflineData();
      }),
    [],
  );

  // Opened offline: renew the session as soon as the phone is online again (a revoked session ends right then).
  useEffect(() => {
    if (!offlineSession) return;
    const retry = () => void restore();
    const network = Network.addNetworkStateListener((state) => {
      if (state.isConnected !== false) retry();
    });
    const appState = AppState.addEventListener('change', (status) => {
      if (status === 'active') retry();
    });
    return () => {
      network.remove();
      appState.remove();
    };
  }, [offlineSession, restore]);

  // Restore on launch: refresh token -> rotated pair -> profile. The splash waits for this.
  useEffect(() => {
    void restore();
    return invalidateRestore;
  }, [restore, invalidateRestore]);

  const completeSignIn = useCallback(
    async (tokens: IssuedTokens, method: SignInMethod = 'otp') => {
      await storeTokens(tokens);
      try {
        const me = await loadMe();
        const remembered = await readRemembered();
        saveQuietly(SIGN_IN_METHOD_STORAGE_KEY, method);
        establish(me, { method, role: remembered.role });
      } catch (error) {
        await clearTokens();
        throw error;
      }
    },
    [establish],
  );

  const setActiveRole = useCallback((role: Role) => {
    dispatch({ type: 'setActiveRole', role });
    if (!previewRef.current) saveQuietly(ACTIVE_ROLE_STORAGE_KEY, role);
  }, []);
  const dismissEndReason = useCallback(() => dispatch({ type: 'dismissEndReason' }), []);

  const signOut = useCallback(async () => {
    const refreshToken = previewRef.current ? null : await readRefreshToken();
    invalidateRestore();
    dispatch({ type: 'signedOut' });
    // Stop pushes to this phone while the access token still works (best effort, bounded in time).
    if (refreshToken) await unregisterStoredPushDevice();
    // Server-side revocation is best effort and must not hold the UI up.
    if (refreshToken) void logout(refreshToken);
    // The outbox and the offline cache hold children's ids and marks: nothing of them outlives the session.
    // Unsent messages and everything of the messaging are memory only: they go first and synchronously.
    clearMessagingMemory();
    await Promise.allSettled([clearTokens(), clearQueryCache(), wipeOfflineData()]);
  }, [invalidateRestore]);

  const startPreview = useCallback((role: Role) => {
    if (!__DEV__) return;
    dispatch({ type: 'startPreview', role, roles: ROLES });
  }, []);

  const value = useMemo<SessionValue>(
    () => ({ ...state, setActiveRole, completeSignIn, retryRestore: restore, dismissEndReason, signOut, startPreview, offlineSession }),
    [state, setActiveRole, completeSignIn, restore, dismissEndReason, signOut, startPreview, offlineSession],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside <SessionProvider>');
  return value;
}
