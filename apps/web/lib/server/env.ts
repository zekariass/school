import "server-only";

/**
 * Origin of the School API (server-only; browsers go through the BFF).
 * Read lazily so `next build` doesn't need the variable.
 */
export function getApiOrigin(): string {
  const value = process.env.API_ORIGIN?.trim();
  if (value) return value.replace(/\/+$/, "");
  if (process.env.NODE_ENV !== "production") return "http://localhost:8180";
  throw new Error(
    "API_ORIGIN is not set. Set it to the School API origin, e.g. https://api.example.com.",
  );
}

/**
 * Storage origins the upload proxy (`PUT /api/bff/uploads/{mediaId}`) may stream to, from
 * `UPLOAD_PROXY_ORIGINS` (comma-separated, server-only). Only used when the web app runs with
 * NEXT_PUBLIC_UPLOAD_MODE=proxy. Defaults to the local SeaweedFS outside production; in
 * production it must list the object store's origin explicitly.
 */
export function getUploadProxyOrigins(): string[] {
  const value = process.env.UPLOAD_PROXY_ORIGINS?.trim();
  if (value) {
    return value
      .split(",")
      .map((origin) => origin.trim().replace(/\/+$/, ""))
      .filter(Boolean);
  }
  return process.env.NODE_ENV !== "production" ? ["http://localhost:8343"] : [];
}
