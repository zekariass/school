import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';
import { authApi } from '@/lib/http';

import type { IssuedTokens } from './refresh';

export interface OtpChallenge {
  codeLength: number;
  /** Seconds the code stays valid. */
  expiresIn: number;
}

/** Asks the backend to send a sign-in code. The answer is the same whether or not an account exists. */
export function requestOtp(phone: string): Promise<OtpChallenge> {
  return request(async () => {
    const accepted = unwrap(await authApi.POST('/api/v1/auth/otp/request', { body: { phone } }));
    return { codeLength: accepted.codeLength, expiresIn: accepted.expiresIn };
  });
}

/** Exchanges the code for a token pair (400 `auth.invalid_code`, 429 `auth.rate_limited`). */
export function verifyOtp(phone: string, code: string): Promise<IssuedTokens> {
  return request(async () => unwrap(await authApi.POST('/api/v1/auth/otp/verify', { body: { phone, code } })));
}

/** The signed-in user's profile and grants. Goes through the authenticated client. */
export function loadMe() {
  return request(async () => unwrap(await api.GET('/api/v1/me')));
}

/** Revokes this device's session on the server. Best effort: callers ignore failures. */
export async function logout(refreshToken: string): Promise<void> {
  try {
    await authApi.POST('/api/v1/auth/logout', { body: { refreshToken } });
  } catch {
    // Offline or timed out: the local sign-out has already happened.
  }
}
