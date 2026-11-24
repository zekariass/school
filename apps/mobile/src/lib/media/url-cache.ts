/**
 * Signed media links live 5 minutes. They are reused for at most 4, never past their own
 * expiry, and never written to the persisted query cache (they would be dead after a restart).
 */

export const URL_MAX_AGE_MS = 4 * 60_000;
export const URL_EXPIRY_MARGIN_MS = 45_000;

/** First element of every media-link query key. */
export const MEDIA_URL_QUERY = 'media-url';

export const mediaUrlKey = (mediaId: string, variant: string) => [MEDIA_URL_QUERY, mediaId, variant] as const;

/** How long a fetched link may be reused (`expiresAt` is the API's ISO instant). */
export function urlStaleTimeMs(expiresAt: string | undefined, now: number): number {
  if (!expiresAt) return 0;
  const expires = Date.parse(expiresAt);
  if (Number.isNaN(expires)) return 0;
  return Math.max(0, Math.min(URL_MAX_AGE_MS, expires - now - URL_EXPIRY_MARGIN_MS));
}

/** Whether a query with this key belongs in the persisted cache (media links do not). */
export function shouldPersistQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== MEDIA_URL_QUERY;
}
