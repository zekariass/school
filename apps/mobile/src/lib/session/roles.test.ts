import { describe, expect, it } from 'vitest';

import { messages } from '@school/i18n';

import {
  ROLE_HOME_ROUTE,
  ROLE_LABEL_KEY,
  ROLES,
  SIGN_IN_ROUTE,
  entryRoute,
  homeRouteFor,
  normalizeRoles,
  resolveActiveRole,
} from './roles';
import { INITIAL_SESSION, sessionReducer } from './state';

describe('role routing', () => {
  it('maps every role to its tab group and an existing label key', () => {
    for (const role of ROLES) {
      expect(homeRouteFor(role)).toMatch(new RegExp(`^/\\(${role}\\)/`));
      const [ns, key] = ROLE_LABEL_KEY[role].split('.') as ['roles', keyof typeof messages.en.roles];
      expect(messages.en[ns][key]).toBeTypeOf('string');
    }
    expect(Object.keys(ROLE_HOME_ROUTE)).toEqual([...ROLES]);
  });

  it('sends signed-out users to sign-in and signed-in users to their active role', () => {
    expect(entryRoute('loading', null)).toBeNull();
    expect(entryRoute('signedOut', null)).toBe(SIGN_IN_ROUTE);
    expect(entryRoute('signedIn', 'guardian')).toBe('/(guardian)/home');
    expect(entryRoute('signedIn', null)).toBe(SIGN_IN_ROUTE);
  });

  it('normalizes role lists and resolves the active role', () => {
    expect(normalizeRoles(['teacher', 'admin', 'teacher', 'student', 7])).toEqual(['teacher', 'student']);
    expect(resolveActiveRole(['teacher', 'student'], 'student')).toBe('student');
    expect(resolveActiveRole(['teacher', 'student'], 'guardian')).toBe('teacher');
    expect(resolveActiveRole([], 'guardian')).toBeNull();
  });
});

describe('sessionReducer', () => {
  it('treats a restored session without roles as signed out', () => {
    expect(sessionReducer(INITIAL_SESSION, { type: 'restored', roles: [] }).status).toBe('signedOut');
    const restored = sessionReducer(INITIAL_SESSION, { type: 'restored', roles: ['guardian', 'teacher'] });
    expect(restored).toMatchObject({ status: 'signedIn', activeRole: 'guardian', preview: false });
  });

  it('switches only to roles the session has', () => {
    const signedIn = sessionReducer(INITIAL_SESSION, { type: 'signedIn', roles: ['teacher', 'guardian'] });
    expect(sessionReducer(signedIn, { type: 'setActiveRole', role: 'guardian' }).activeRole).toBe('guardian');
    expect(sessionReducer(signedIn, { type: 'setActiveRole', role: 'student' })).toBe(signedIn);
  });

  it('starts an in-memory preview and clears everything on sign out', () => {
    const preview = sessionReducer(INITIAL_SESSION, {
      type: 'startPreview',
      role: 'student',
      roles: ROLES,
    });
    expect(preview).toMatchObject({ status: 'signedIn', activeRole: 'student', preview: true });
    expect(sessionReducer(preview, { type: 'signedOut' })).toEqual({
      status: 'signedOut',
      roles: [],
      activeRole: null,
      preview: false,
      profile: null,
      endReason: null,
      signInMethod: null,
      offline: false,
    });
  });
});
