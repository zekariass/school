import { sanitizeNextPath } from "@/lib/safe-redirect";

/**
 * The in-app route a notification opens, from its `data.path`. Only same-origin relative
 * paths pass (see sanitizeNextPath); anything else is null and the item just gets marked read.
 * Paths in notifications carry no locale: the localized router adds it.
 */
export function resolveDeepLink(data: unknown): string | null {
  if (typeof data !== "object" || data === null) return null;
  const path = (data as { path?: unknown }).path;
  return typeof path === "string" ? sanitizeNextPath(path) : null;
}
