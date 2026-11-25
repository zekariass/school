import { sanitizeNextPath } from "@/lib/safe-redirect";

/**
 * The in-app route a notification opens, from its `data.path`. Only same-origin relative
 * paths pass (see sanitizeNextPath); anything else is null and the item just gets marked read.
 * Paths in notifications carry no locale: the localized router adds it.
 */
export function resolveDeepLink(data: unknown): string | null {
  if (typeof data !== "object" || data === null) return null;
  const path = (data as { path?: unknown }).path;
  const safe = typeof path === "string" ? sanitizeNextPath(path) : null;
  return safe ? (WEB_PATHS[safe.split(/[?#]/)[0] ?? ""] ?? safe) : null;
}

/** Paths the API sends for guardians (see `GuardianNotifications`, `ConsentRequests`) and where the web shows them. */
const WEB_PATHS: Record<string, string> = {
  "/children": "/family",
  "/consents": "/family/consent",
};
