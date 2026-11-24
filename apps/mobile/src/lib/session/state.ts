import type { SessionEndReason } from '@/lib/auth/session-events';

import type { AccessKind } from './me-roles';
import { normalizeRoles, resolveActiveRole, type Role, type SessionStatus } from './roles';

/** Who is signed in, as far as the shell needs it. */
export interface SessionProfile {
  displayName: string;
  accessKind: AccessKind;
}

export interface SessionState {
  status: SessionStatus;
  roles: Role[];
  activeRole: Role | null;
  /** True for the in-memory dev role previewer: no tokens, never persisted. */
  preview: boolean;
  /** The signed-in account; null for signed-out and for the dev preview. */
  profile: SessionProfile | null;
  /** Why the last session ended without the user asking (shown once on the sign-in screen). */
  endReason: SessionEndReason | null;
}

export const INITIAL_SESSION: SessionState = {
  status: 'loading',
  roles: [],
  activeRole: null,
  preview: false,
  profile: null,
  endReason: null,
};

export type SessionAction =
  | { type: 'restored'; roles: readonly unknown[]; profile?: SessionProfile }
  | { type: 'signedIn'; roles: readonly unknown[]; preferredRole?: Role | null; profile?: SessionProfile }
  | { type: 'setActiveRole'; role: Role }
  | { type: 'signedOut'; reason?: SessionEndReason }
  | { type: 'unreachable' }
  | { type: 'dismissEndReason' }
  | { type: 'startPreview'; role: Role; roles: readonly Role[] };

export function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case 'restored':
    case 'signedIn': {
      const roles = normalizeRoles(action.roles);
      const activeRole = resolveActiveRole(roles, action.type === 'signedIn' ? action.preferredRole : null);
      const profile = action.profile ?? null;
      // No usable role and no known account: nothing to show, treat it as signed out.
      // With a known account the user is signed in without a shell (`no-app-role` screen).
      if (!activeRole && !profile) return { ...INITIAL_SESSION, status: 'signedOut' };
      return { status: 'signedIn', roles, activeRole, preview: false, profile, endReason: null };
    }
    case 'setActiveRole':
      if (state.status !== 'signedIn' || !state.roles.includes(action.role)) return state;
      return { ...state, activeRole: action.role };
    case 'signedOut':
      return { ...INITIAL_SESSION, status: 'signedOut', endReason: action.reason ?? null };
    case 'unreachable':
      return { ...INITIAL_SESSION, status: 'unreachable' };
    case 'dismissEndReason':
      return state.endReason ? { ...state, endReason: null } : state;
    case 'startPreview': {
      const roles = normalizeRoles(action.roles);
      if (!roles.includes(action.role)) return state;
      return { status: 'signedIn', roles, activeRole: action.role, preview: true, profile: null, endReason: null };
    }
  }
}
