/**
 * Rules of the upload proxy (`PUT /api/bff/uploads/{mediaId}`, proxy mode only). The browser
 * names the presigned URL to PUT to; the proxy must never become a way to reach other hosts,
 * so the target has to be an allowed storage origin and has to belong to that media's object.
 */

/** The largest body the proxy forwards (the API's biggest upload limit, 100 MB, plus slack). */
export const MAX_PROXY_BYTES = 101 * 1024 * 1024;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isMediaId(value: string): boolean {
  return UUID.test(value);
}

/** The target URL when it may be used for this media, else null. */
export function checkUploadTarget(
  target: string | null,
  mediaId: string,
  allowedOrigins: readonly string[],
): URL | null {
  if (!target || !isMediaId(mediaId)) return null;
  let url: URL;
  try {
    url = new URL(target);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return null;
  if (url.username || url.password) return null;
  if (!allowedOrigins.includes(url.origin)) return null;
  // Object keys end in /{mediaId}/original.
  const segments = url.pathname.split("/");
  if (segments.at(-2)?.toLowerCase() !== mediaId.toLowerCase() || segments.at(-1) !== "original") return null;
  // It must be a presigned URL, not an anonymous request.
  if (!url.searchParams.has("X-Amz-Signature")) return null;
  return url;
}

/** A valid Content-Length within the proxy's limit, else null. */
export function parseContentLength(value: string | null): number | null {
  if (!value || !/^\d+$/.test(value)) return null;
  const length = Number(value);
  return length > 0 && length <= MAX_PROXY_BYTES ? length : null;
}
