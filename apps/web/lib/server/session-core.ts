/**
 * Session primitives shared by `proxy.ts`, Route Handlers and Server Components:
 * cookie names and options, JWT expiry reading and the refresh decision.
 *
 * Deliberately free of `server-only` and `next/headers` so that the proxy (which
 * runs in its own bundle) and unit tests can import it. Nothing here verifies
 * signatures: the access token's `exp` is only used to decide whether to refresh;
 * the API is the authority on whether a token is valid.
 */

export const ACCESS_TOKEN_COOKIE = "sch_at";
export const REFRESH_TOKEN_COOKIE = "sch_rt";

/** Refresh when the access token has this many seconds (or fewer) left. */
export const REFRESH_SKEW_SECONDS = 60;

export interface SessionTokens {
  accessToken: string;
  /** Seconds. */
  accessTokenMaxAge: number;
  refreshToken: string;
  /** Seconds. */
  refreshTokenMaxAge: number;
}

export interface SessionCookieOptions {
  httpOnly: true;
  secure: boolean;
  sameSite: "lax";
  path: "/";
  maxAge: number;
}

/**
 * httpOnly (no JavaScript access), `Secure` in production, `SameSite=Lax` and
 * site-wide. `maxAge` is in seconds and is clamped to at least 1 so a bad
 * response from the API can never produce a session cookie.
 */
export function buildSessionCookieOptions(maxAgeSeconds: number, secure: boolean): SessionCookieOptions {
  return {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: Math.max(1, Math.floor(maxAgeSeconds)),
  };
}

export function isSecureEnvironment(): boolean {
  return process.env.NODE_ENV === "production";
}

/** Maps the API's token response onto cookie lifetimes. */
export function tokensFromResponse(body: unknown): SessionTokens | null {
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  const { accessToken, refreshToken, accessTokenExpiresIn, refreshTokenExpiresIn } = b;
  if (
    typeof accessToken !== "string" ||
    typeof refreshToken !== "string" ||
    typeof accessTokenExpiresIn !== "number" ||
    typeof refreshTokenExpiresIn !== "number" ||
    !accessToken ||
    !refreshToken
  ) {
    return null;
  }
  return {
    accessToken,
    refreshToken,
    accessTokenMaxAge: accessTokenExpiresIn,
    refreshTokenMaxAge: refreshTokenExpiresIn,
  };
}

/**
 * The `exp` claim (seconds since the epoch) of a JWT, read WITHOUT verifying the
 * signature, or null when the token is malformed. Never trust the result for
 * anything but scheduling a refresh.
 */
export function readJwtExpiry(token: string | null | undefined): number | null {
  if (!token) return null;
  const parts = token.split(".");
  if (parts.length !== 3 || !parts[1]) return null;
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const json = new TextDecoder().decode(Uint8Array.from(atob(padded), (c) => c.charCodeAt(0)));
    const payload: unknown = JSON.parse(json);
    if (typeof payload !== "object" || payload === null) return null;
    const exp = (payload as { exp?: unknown }).exp;
    return typeof exp === "number" && Number.isFinite(exp) ? exp : null;
  } catch {
    return null;
  }
}

export type RefreshDecision = "none" | "refresh";

/**
 * Refresh when a refresh token exists and the access token is missing, unreadable
 * or about to expire. Without a refresh token there is nothing to do (the
 * request proceeds and the API answers 401 if the access token is bad).
 */
export function decideRefresh(input: {
  accessToken: string | null | undefined;
  refreshToken: string | null | undefined;
  /** Epoch milliseconds. */
  now: number;
  skewSeconds?: number;
}): RefreshDecision {
  if (!input.refreshToken) return "none";
  const exp = readJwtExpiry(input.accessToken);
  if (exp === null) return "refresh";
  const skew = input.skewSeconds ?? REFRESH_SKEW_SECONDS;
  return exp * 1000 - input.now <= skew * 1000 ? "refresh" : "none";
}
