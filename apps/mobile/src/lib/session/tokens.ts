import * as SecureStore from 'expo-secure-store';

import { expiryFrom } from '@/lib/auth/token-expiry';

/** SecureStore keys. Only the refresh token is persisted; access tokens stay in memory. */
export const REFRESH_TOKEN_KEY = 'school.refreshToken';

let accessToken: string | null = null;
/** Wall-clock ms at which the in-memory access token expires. */
let accessTokenExpiresAt: number | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function getAccessTokenExpiry(): number | null {
  return accessTokenExpiresAt;
}

export async function readRefreshToken(): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Stores a freshly issued token pair (sign-in or refresh rotation): the access token and
 * its expiry stay in memory, the refresh token goes to the secure store. Throws if the
 * refresh token cannot be written; the in-memory access token is set regardless.
 */
export async function storeTokens(tokens: {
  accessToken: string;
  accessTokenExpiresIn: number;
  refreshToken: string;
}): Promise<void> {
  accessToken = tokens.accessToken;
  accessTokenExpiresAt = expiryFrom(Date.now(), tokens.accessTokenExpiresIn);
  await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, tokens.refreshToken);
}

/** Forgets both tokens (sign out, refresh failure). */
export async function clearTokens(): Promise<void> {
  accessToken = null;
  accessTokenExpiresAt = null;
  try {
    await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
  } catch {
    // Nothing stored or the keystore is unavailable: the in-memory token is already gone.
  }
}
