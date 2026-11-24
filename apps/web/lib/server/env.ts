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
