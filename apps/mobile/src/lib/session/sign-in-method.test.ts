import { describe, expect, it } from 'vitest';

import { SIGN_IN_ROUTE, entryRoute } from './roles';
import { STUDENT_CODE_ROUTE, isSignInMethod, signedOutRoute } from './sign-in-method';

describe('signedOutRoute', () => {
  it('sends class-code sessions back to the code screen, everything else to the phone screen', () => {
    expect(signedOutRoute('loginCode')).toBe(STUDENT_CODE_ROUTE);
    expect(signedOutRoute('otp')).toBe(SIGN_IN_ROUTE);
    expect(signedOutRoute(null)).toBe(SIGN_IN_ROUTE);
    expect(signedOutRoute(undefined)).toBe(SIGN_IN_ROUTE);
  });

  it('is what the entry route uses for signed-out users only', () => {
    expect(entryRoute('signedOut', null, false, signedOutRoute('loginCode'))).toBe(STUDENT_CODE_ROUTE);
    expect(entryRoute('signedIn', 'student', true, signedOutRoute('loginCode'))).toBe('/(student)/home');
    expect(entryRoute('loading', null, false, signedOutRoute('loginCode'))).toBeNull();
  });
});

describe('isSignInMethod', () => {
  it('accepts only the two known methods', () => {
    expect(isSignInMethod('otp')).toBe(true);
    expect(isSignInMethod('loginCode')).toBe(true);
    expect(isSignInMethod('password')).toBe(false);
    expect(isSignInMethod(null)).toBe(false);
  });
});
