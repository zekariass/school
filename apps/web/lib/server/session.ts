import "server-only";
import { cookies } from "next/headers";

export const ACCESS_TOKEN_COOKIE = "sch_at";
export const REFRESH_TOKEN_COOKIE = "sch_rt";

const baseOptions = {
  httpOnly: true,
  secure: process.env.NODE_ENV === "production",
  sameSite: "lax",
  path: "/",
} as const;

export async function getAccessToken(): Promise<string | null> {
  const store = await cookies();
  return store.get(ACCESS_TOKEN_COOKIE)?.value ?? null;
}

export interface SessionTokens {
  accessToken: string;
  /** Seconds. */
  accessTokenMaxAge: number;
  refreshToken: string;
  /** Seconds. */
  refreshTokenMaxAge: number;
}

/** Server Actions and Route Handlers only: cookies can't be set while rendering. */
export async function setSessionCookies(tokens: SessionTokens): Promise<void> {
  const store = await cookies();
  store.set(ACCESS_TOKEN_COOKIE, tokens.accessToken, {
    ...baseOptions,
    maxAge: tokens.accessTokenMaxAge,
  });
  store.set(REFRESH_TOKEN_COOKIE, tokens.refreshToken, {
    ...baseOptions,
    maxAge: tokens.refreshTokenMaxAge,
  });
}

/** Server Actions and Route Handlers only. */
export async function clearSessionCookies(): Promise<void> {
  const store = await cookies();
  store.delete(ACCESS_TOKEN_COOKIE);
  store.delete(REFRESH_TOKEN_COOKIE);
}
