/**
 * Pure helpers for the BFF route handler (app/api/bff/[...path]/route.ts):
 * the browser talks to /api/bff/api/v1/… and the handler forwards to the API.
 * Everything here is allowlist-based so the BFF can't become an open proxy.
 */

const SEGMENT = /^[A-Za-z0-9._~-]+$/;

const REQUEST_HEADER_ALLOWLIST = [
  "accept",
  "accept-language",
  "content-type",
  "idempotency-key",
  "x-request-id",
] as const;

const RESPONSE_HEADER_ALLOWLIST = [
  "content-type",
  "x-request-id",
  "cache-control",
  "retry-after",
  "idempotent-replayed",
] as const;

/** Only the versioned public API is reachable (never actuator, docs, etc.). */
export function isAllowedUpstreamPath(segments: readonly string[]): boolean {
  if (segments.length < 3) return false;
  if (segments[0] !== "api" || segments[1] !== "v1") return false;
  return segments.every((s) => s !== "." && s !== ".." && SEGMENT.test(s));
}

/** Upstream URL for a request, or null when the path isn't allowed. */
export function buildUpstreamUrl(
  origin: string,
  segments: readonly string[],
  search: string,
): URL | null {
  if (!isAllowedUpstreamPath(segments)) return null;
  const base = origin.replace(/\/+$/, "");
  return new URL(`${base}/${segments.join("/")}${search}`);
}

/**
 * Headers forwarded upstream: an allowlist, so the browser's Cookie and
 * Authorization never reach the API. The BFF attaches its own bearer token.
 */
export function buildUpstreamHeaders(
  incoming: Headers,
  accessToken?: string | null,
): Headers {
  const out = new Headers();
  for (const name of REQUEST_HEADER_ALLOWLIST) {
    const value = incoming.get(name);
    if (value) out.set(name, value);
  }
  if (accessToken) out.set("authorization", `Bearer ${accessToken}`);
  return out;
}

export function filterResponseHeaders(upstream: Headers): Headers {
  const out = new Headers();
  for (const name of RESPONSE_HEADER_ALLOWLIST) {
    const value = upstream.get(name);
    if (value) out.set(name, value);
  }
  return out;
}

export interface BffProblem {
  type: string;
  title: string;
  status: number;
  code: string;
}

export function bffProblem(code: string, status: number, title: string): BffProblem {
  return { type: `urn:school:problem:${code}`, title, status, code };
}
