import { describe, expect, it } from 'vitest';

import { resolveSessionFromMe } from './me-roles';
import { NO_APP_ROLE_ROUTE, entryRoute } from './roles';
import { INITIAL_SESSION, sessionReducer } from './state';

const admin = { displayName: 'Dev Admin', roleAssignments: [{ roleCode: 'PLATFORM_ADMIN' }] };

describe('resolveSessionFromMe', () => {
  it('gives web-only administrators no mobile role', () => {
    expect(resolveSessionFromMe(admin)).toEqual({ roles: [], activeRole: null, accessKind: 'admin' });
  });

  it('distinguishes accounts without any role', () => {
    expect(resolveSessionFromMe({ displayName: 'New', roleAssignments: [] }).accessKind).toBe('none');
  });

  it('maps roles through the given table and honours the preferred role', () => {
    const mapping = { TEACHER: 'teacher', GUARDIAN: 'guardian' } as const;
    const me = {
      displayName: 'T',
      roleAssignments: [{ roleCode: 'GUARDIAN' }, { roleCode: 'SCHOOL_ADMIN' }, { roleCode: 'TEACHER' }, { roleCode: 'TEACHER' }],
    };
    expect(resolveSessionFromMe(me, null, mapping)).toEqual({
      roles: ['guardian', 'teacher'],
      activeRole: 'guardian',
      accessKind: 'app',
    });
    expect(resolveSessionFromMe(me, 'teacher', mapping).activeRole).toBe('teacher');
  });
});

describe('signed-in session without an app role', () => {
  const profile = { displayName: 'Dev Admin', accessKind: 'admin' as const };

  it('is signed in, with a profile and no active role', () => {
    const state = sessionReducer(INITIAL_SESSION, { type: 'signedIn', roles: [], profile });
    expect(state).toMatchObject({ status: 'signedIn', activeRole: null, roles: [], profile, preview: false });
    expect(entryRoute(state.status, state.activeRole, state.profile !== null)).toBe(NO_APP_ROLE_ROUTE);
  });

  it('keeps routing a roleless anonymous state to sign-in', () => {
    expect(entryRoute('signedIn', null)).toBe('/(auth)/sign-in');
    expect(entryRoute('unreachable', null)).toBeNull();
  });

  it('remembers why a session ended until the next sign-in', () => {
    const out = sessionReducer(INITIAL_SESSION, { type: 'signedOut', reason: 'revoked' });
    expect(out).toMatchObject({ status: 'signedOut', endReason: 'revoked' });
    expect(sessionReducer(out, { type: 'dismissEndReason' }).endReason).toBeNull();
    expect(sessionReducer(out, { type: 'signedIn', roles: [], profile }).endReason).toBeNull();
  });

  it('has an unreachable state that carries no profile', () => {
    expect(sessionReducer(INITIAL_SESSION, { type: 'unreachable' })).toMatchObject({ status: 'unreachable', profile: null });
  });
});
