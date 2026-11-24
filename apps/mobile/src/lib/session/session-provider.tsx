import { createContext, useCallback, useContext, useEffect, useMemo, useReducer, type ReactNode } from 'react';

import { clearQueryCache } from '@/lib/query/query-client';

import { ROLES, type Role } from './roles';
import { INITIAL_SESSION, sessionReducer, type SessionState } from './state';
import { clearTokens, readRefreshToken } from './tokens';

export interface SessionValue extends SessionState {
  setActiveRole: (role: Role) => void;
  /** Clears tokens, the session and cached server data. */
  signOut: () => Promise<void>;
  /** DEVELOPMENT ONLY: in-memory session with every role, no tokens. No-op in release builds. */
  startPreview: (role: Role) => void;
}

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(sessionReducer, INITIAL_SESSION);

  // Restore on launch. Token issuance and refresh arrive with M1 (auth): until the
  // API can tell us who the token belongs to, a stored refresh token has no roles
  // and the app goes to sign-in.
  useEffect(() => {
    let cancelled = false;
    readRefreshToken().then(() => {
      if (!cancelled) dispatch({ type: 'restored', roles: [] });
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const setActiveRole = useCallback((role: Role) => dispatch({ type: 'setActiveRole', role }), []);

  const signOut = useCallback(async () => {
    dispatch({ type: 'signedOut' });
    await Promise.allSettled([clearTokens(), clearQueryCache()]);
  }, []);

  const startPreview = useCallback((role: Role) => {
    if (!__DEV__) return;
    dispatch({ type: 'startPreview', role, roles: ROLES });
  }, []);

  const value = useMemo<SessionValue>(
    () => ({ ...state, setActiveRole, signOut, startPreview }),
    [state, setActiveRole, signOut, startPreview],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside <SessionProvider>');
  return value;
}
