import type { components } from "@school/api-client";

export type MediaVariantName = Exclude<components["schemas"]["UrlResponse"]["variant"], "ORIGINAL">;

/** Signed links live 5 minutes; keep using a fetched one for at most 4. */
export const URL_MAX_AGE_MS = 4 * 60_000;

/** Never use a link that has less than this left (it may expire while the image loads). */
export const URL_EXPIRY_MARGIN_MS = 45_000;

export const mediaUrlKey = (mediaId: string, variant: string) => ["media-url", mediaId, variant] as const;

/**
 * How long a fetched signed link may be reused: the shorter of 4 minutes and the time left
 * before it expires (minus a margin), never negative. `expiresAt` is the API's ISO instant.
 */
export function urlStaleTimeMs(expiresAt: string | undefined, now: number): number {
  if (!expiresAt) return 0;
  const expires = Date.parse(expiresAt);
  if (Number.isNaN(expires)) return 0;
  return Math.max(0, Math.min(URL_MAX_AGE_MS, expires - now - URL_EXPIRY_MARGIN_MS));
}

/** True when a cached link can still be shown. */
export function isUrlUsable(expiresAt: string | undefined, now: number): boolean {
  return urlStaleTimeMs(expiresAt, now) > 0;
}
