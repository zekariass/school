/**
 * How the browser sends the file bytes (NEXT_PUBLIC_UPLOAD_MODE):
 * - `direct` (default): a PUT straight to the presigned storage URL. Needs a CORS rule on the
 *   bucket for this site's origin (local development sets it up).
 * - `proxy`: the bytes go through this site's BFF (`PUT /api/bff/uploads/{mediaId}`), which
 *   streams them to the presigned URL. Use it where the bucket cannot allow this origin.
 */
export type UploadMode = "direct" | "proxy";

export function resolveUploadMode(value: string | undefined): UploadMode {
  return value?.trim().toLowerCase() === "proxy" ? "proxy" : "direct";
}

/** Where the browser PUTs the bytes, and the extra header the proxy needs. */
export function uploadTarget(
  mode: UploadMode,
  mediaId: string,
  presignedUrl: string,
): { url: string; extraHeaders: Record<string, string> } {
  if (mode === "direct") return { url: presignedUrl, extraHeaders: {} };
  return {
    url: `/api/bff/uploads/${encodeURIComponent(mediaId)}`,
    extraHeaders: { "x-upload-target": presignedUrl },
  };
}
