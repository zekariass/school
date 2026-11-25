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
  if (!safe) return null;
  const base = safe.split(/[?#]/)[0] ?? "";
  // A guardian's "your child got points" opens that child's page when the notification says which child.
  const studentId = (data as { studentId?: unknown }).studentId;
  if (base === "/children" && typeof studentId === "string" && UUID.test(studentId)) return `/family/children/${studentId}`;
  return WEB_PATHS[base] ?? safe;
}

/** Paths the API sends for guardians (see `GuardianNotifications`, `ConsentRequests`) and where the web shows them. */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const WEB_PATHS: Record<string, string> = {
  "/children": "/family",
  "/consents": "/family/consent",
};
