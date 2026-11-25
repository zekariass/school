/** How the current (or last) session was started. Decides where a signed-out user lands. */
export const SIGN_IN_METHODS = ['otp', 'loginCode'] as const;
export type SignInMethod = (typeof SIGN_IN_METHODS)[number];

/** kv-store keys (not secrets): the last sign-in method and the role last used. */
export const SIGN_IN_METHOD_STORAGE_KEY = 'school.signInMethod';
export const ACTIVE_ROLE_STORAGE_KEY = 'school.activeRole';

export function isSignInMethod(value: unknown): value is SignInMethod {
  return value === 'otp' || value === 'loginCode';
}

export const STUDENT_CODE_ROUTE = '/(auth)/student-code';

/**
 * The screen a signed-out user returns to: students who signed in with a class code come back to
 * the code screen (their session is short, so expiry is routine), everybody else to the phone screen.
 */
export function signedOutRoute(method: SignInMethod | null | undefined): string {
  return method === 'loginCode' ? STUDENT_CODE_ROUTE : '/(auth)/sign-in';
}
