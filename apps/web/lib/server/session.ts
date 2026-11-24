import "server-only";
import { cookies } from "next/headers";
import {
  ACCESS_TOKEN_COOKIE,
  REFRESH_TOKEN_COOKIE,
  buildSessionCookieOptions,
  isSecureEnvironment,
  type SessionTokens,
} from "./session-core";

export { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE, type SessionTokens } from "./session-core";

export async function getAccessToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(ACCESS_TOKEN_COOKIE)?.value ?? null;
}

export async function getRefreshToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(REFRESH_TOKEN_COOKIE)?.value ?? null;
}

/** Server Actions and Route Handlers only: cookies can't be set while rendering. */
export async function setSessionCookies(tokens: SessionTokens): Promise<void> {
  const store = await cookies();
  const secure = isSecureEnvironment();
  store.set(
    ACCESS_TOKEN_COOKIE,
    tokens.accessToken,
    buildSessionCookieOptions(tokens.accessTokenMaxAge, secure),
  );
  store.set(
    REFRESH_TOKEN_COOKIE,
    tokens.refreshToken,
    buildSessionCookieOptions(tokens.refreshTokenMaxAge, secure),
  );
}

/** Server Actions and Route Handlers only. */
export async function clearSessionCookies(): Promise<void> {
  const store = await cookies();
  store.delete(ACCESS_TOKEN_COOKIE);
  store.delete(REFRESH_TOKEN_COOKIE);
}
