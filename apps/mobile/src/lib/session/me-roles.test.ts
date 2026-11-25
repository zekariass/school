import { describe, expect, it } from 'vitest';

import { resolveSessionFromMe } from './me-roles';
import { NO_APP_ROLE_ROUTE, entryRoute } from './roles';
import { signedOutRoute } from './sign-in-method';
import { INITIAL_SESSION, sessionReducer } from './state';

const admin = { displayName: 'Dev Admin', roleAssignments: [{ roleCode: 'PLATFORM_ADMIN' }] };

describe('resolveSessionFromMe', () => {
  it('gives web-only administrators no mobile role', () => {
    expect(resolveSessionFromMe(admin)).toEqual({ roles: [], activeRole: null, accessKind: 'admin' });
  });

  it('gives an account that holds nothing yet "My children" (a waiting parent, or one yet to connect)', () => {
    expect(resolveSessionFromMe({ displayName: 'New', roleAssignments: [] })).toEqual({
      roles: ['guardian'],
      activeRole: 'guardian',
      accessKind: 'app',
    });
  });

  it('maps teacher and student memberships to mobile roles', () => {
    const teacher = { displayName: 'T', roleAssignments: [], memberships: [{ type: 'TEACHER' }] };
    const student = { displayName: 'S', roleAssignments: [], memberships: [{ type: 'STUDENT' }, { type: 'STUDENT' }] };
    expect(resolveSessionFromMe(teacher)).toEqual({ roles: ['teacher'], activeRole: 'teacher', accessKind: 'app' });
    expect(resolveSessionFromMe(student)).toEqual({ roles: ['student'], activeRole: 'student', accessKind: 'app' });
  });

  it('gives an account with several memberships every role, in a stable order, and remembers the last one', () => {
    const both = { displayName: 'B', roleAssignments: [], memberships: [{ type: 'STUDENT' }, { type: 'TEACHER' }] };
    expect(resolveSessionFromMe(both)).toEqual({ roles: ['teacher', 'student'], activeRole: 'teacher', accessKind: 'app' });
    expect(resolveSessionFromMe(both, 'student').activeRole).toBe('student');
    // A remembered role the account no longer has is ignored.
    const teacherOnly = { displayName: 'T', roleAssignments: [], memberships: [{ type: 'TEACHER' }] };
    expect(resolveSessionFromMe(teacherOnly, 'student').activeRole).toBe('teacher');
  });

  it('treats STAFF memberships and administrator grants alone as web-only', () => {
    const staff = { displayName: 'S', roleAssignments: [], memberships: [{ type: 'STAFF' }] };
    expect(resolveSessionFromMe(staff)).toEqual({ roles: [], activeRole: null, accessKind: 'admin' });
    const adminTeacher = { displayName: 'A', roleAssignments: [{ roleCode: 'SCHOOL_ADMIN' }], memberships: [{ type: 'TEACHER' }] };
    expect(resolveSessionFromMe(adminTeacher)).toMatchObject({ roles: ['teacher'], accessKind: 'app' });
  });

  it('maps a GUARDIAN membership to the guardian role', () => {
    const me = { displayName: 'G', roleAssignments: [], memberships: [{ type: 'GUARDIAN' }, { type: 'GUARDIAN' }] };
    expect(resolveSessionFromMe(me)).toEqual({ roles: ['guardian'], activeRole: 'guardian', accessKind: 'app' });
  });

  it('puts the guardian role between teacher and student, for a parent who also teaches', () => {
    const me = { displayName: 'G', roleAssignments: [], memberships: [{ type: 'STUDENT' }, { type: 'GUARDIAN' }, { type: 'TEACHER' }] };
    expect(resolveSessionFromMe(me).roles).toEqual(['teacher', 'guardian', 'student']);
  });

  it('keeps administrators and staff without the family fallback', () => {
    expect(resolveSessionFromMe({ displayName: 'A', roleAssignments: [{ roleCode: 'SCHOOL_ADMIN' }] }).roles).toEqual([]);
    expect(resolveSessionFromMe({ displayName: 'S', roleAssignments: [], memberships: [{ type: 'STAFF' }] }).roles).toEqual([]);
  });

  it('maps roles through the given table and honours the preferred role', () => {
    const mapping = { TEACHER: 'teacher', GUARDIAN: 'guardian' } as const;
    const me = {
      displayName: 'T',
      roleAssignments: [{ roleCode: 'GUARDIAN' }, { roleCode: 'SCHOOL_ADMIN' }, { roleCode: 'TEACHER' }, { roleCode: 'TEACHER' }],
    };
    expect(resolveSessionFromMe(me, null, mapping)).toEqual({
      roles: ['teacher', 'guardian'],
      activeRole: 'teacher',
      accessKind: 'app',
    });
    expect(resolveSessionFromMe(me, 'guardian', mapping).activeRole).toBe('guardian');
  });
});

describe('sign-in method and remembered role in the session state', () => {
  const profile = { displayName: 'Abebe', accessKind: 'app' as const };

  it('keeps the sign-in method after sign-out so the right screen is shown', () => {
    const signedIn = sessionReducer(INITIAL_SESSION, { type: 'signedIn', roles: ['student'], profile, signInMethod: 'loginCode' });
    expect(signedIn.signInMethod).toBe('loginCode');
    const out = sessionReducer(signedIn, { type: 'signedOut', reason: 'expired' });
    expect(out).toMatchObject({ status: 'signedOut', signInMethod: 'loginCode', endReason: 'expired' });
    expect(signedOutRoute(out.signInMethod)).toBe('/(auth)/student-code');
    // The remembered method from a previous launch also routes a session that ended while the app was closed.
    const cold = sessionReducer(INITIAL_SESSION, { type: 'signedOut', signInMethod: 'loginCode' });
    expect(entryRoute(cold.status, cold.activeRole, false, signedOutRoute(cold.signInMethod))).toBe('/(auth)/student-code');
  });

  it('opens with the remembered role when restoring a session', () => {
    const restored = sessionReducer(INITIAL_SESSION, { type: 'restored', roles: ['teacher', 'student'], preferredRole: 'student', profile });
    expect(restored.activeRole).toBe('student');
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
