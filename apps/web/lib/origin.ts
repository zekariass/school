/**
 * Origin check for state-changing requests to our Route Handlers (/api/auth/*,
 * /api/bff/*). `SameSite=Lax` cookies already stop most cross-site POSTs; this
 * is defense in depth that also covers same-site sibling origins.
 *
 * Rules, for POST/PUT/PATCH/DELETE (everything else is allowed):
 *  - `Origin` present: it must equal the app's own origin ("null" never does).
 *  - `Origin` absent: `Sec-Fetch-Site` must be `same-origin`.
 *  - neither: rejected (a browser always sends one of them).
 * The app's origin is `APP_ORIGIN` when set (for proxied deployments where the
 * request URL shows the internal host), else the origin of the request URL.
 */

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type OriginVerdict = { ok: true } | { ok: false; reason: string };

export function expectedOrigin(requestUrl: string, appOriginOverride?: string | null): string | null {
  const override = appOriginOverride?.trim();
  try {
    if (override) return new URL(override).origin;
    return new URL(requestUrl).origin;
  } catch {
    return null;
  }
}

export function checkOrigin(input: {
  method: string;
  requestUrl: string;
  headers: Pick<Headers, "get">;
  appOrigin?: string | null;
}): OriginVerdict {
  if (SAFE_METHODS.has(input.method.toUpperCase())) return { ok: true };

  const expected = expectedOrigin(input.requestUrl, input.appOrigin);
  if (!expected) return { ok: false, reason: "no-expected-origin" };

  const origin = input.headers.get("origin");
  if (origin !== null) {
    return origin === expected ? { ok: true } : { ok: false, reason: "origin-mismatch" };
  }
  const site = input.headers.get("sec-fetch-site");
  if (site === "same-origin") return { ok: true };
  return { ok: false, reason: site ? "cross-site" : "no-origin" };
}
