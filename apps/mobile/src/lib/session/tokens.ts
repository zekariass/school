import * as SecureStore from 'expo-secure-store';

/** SecureStore keys. Only the refresh token is persisted; access tokens stay in memory. */
export const REFRESH_TOKEN_KEY = 'school.refreshToken';

let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export async function readRefreshToken(): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Stores a freshly issued token pair. Called by the auth flow (M1); nothing issues
 * tokens yet.
 */
export async function storeTokens(tokens: { accessToken: string; refreshToken: string }): Promise<void> {
  accessToken = tokens.accessToken;
  await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, tokens.refreshToken);
}

/** Forgets both tokens (sign out, refresh failure). */
export async function clearTokens(): Promise<void> {
  accessToken = null;
  try {
    await SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY);
  } catch {
    // Nothing stored or the keystore is unavailable: the in-memory token is already gone.
  }
}
