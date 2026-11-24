import { normalizeRoles, resolveActiveRole, type Role, type SessionStatus } from './roles';

export interface SessionState {
  status: SessionStatus;
  roles: Role[];
  activeRole: Role | null;
  /** True for the in-memory dev role previewer: no tokens, never persisted. */
  preview: boolean;
}

export const INITIAL_SESSION: SessionState = {
  status: 'loading',
  roles: [],
  activeRole: null,
  preview: false,
};

export type SessionAction =
  | { type: 'restored'; roles: readonly unknown[] }
  | { type: 'signedIn'; roles: readonly unknown[]; preferredRole?: Role | null }
  | { type: 'setActiveRole'; role: Role }
  | { type: 'signedOut' }
  | { type: 'startPreview'; role: Role; roles: readonly Role[] };

export function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case 'restored':
    case 'signedIn': {
      const roles = normalizeRoles(action.roles);
      const activeRole = resolveActiveRole(roles, action.type === 'signedIn' ? action.preferredRole : null);
      // A session with no usable role cannot show any shell: treat it as signed out.
      if (!activeRole) return { ...INITIAL_SESSION, status: 'signedOut' };
      return { status: 'signedIn', roles, activeRole, preview: false };
    }
    case 'setActiveRole':
      if (state.status !== 'signedIn' || !state.roles.includes(action.role)) return state;
      return { ...state, activeRole: action.role };
    case 'signedOut':
      return { ...INITIAL_SESSION, status: 'signedOut' };
    case 'startPreview': {
      const roles = normalizeRoles(action.roles);
      if (!roles.includes(action.role)) return state;
      return { status: 'signedIn', roles, activeRole: action.role, preview: true };
    }
  }
}
