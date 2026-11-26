import { sanitizeNextPath } from "@/lib/safe-redirect";

/**
 * The in-app route a notification opens, from its `data.path`. Only same-origin relative
 * paths pass (see sanitizeNextPath); anything else is null and the item just gets marked read.
 * Paths in notifications carry no locale: the localized router adds it.
 */
export function resolveDeepLink(
  data: unknown,
  context: { schoolSlug?: string | null; schoolId?: string | null } = {},
): string | null {
  if (typeof data !== "object" || data === null) return null;
  const path = (data as { path?: unknown }).path;
  const safe = typeof path === "string" ? sanitizeNextPath(path) : null;
  if (!safe) return null;
  const base = safe.split(/[?#]/)[0] ?? "";
  // A guardian's "your child got points" opens that child's page when the notification says which child.
  const studentId = (data as { studentId?: unknown }).studentId;
  if (base === "/children" && typeof studentId === "string" && UUID.test(studentId)) {
    // An attendance notification names the day too: it opens the attendance section of the child's page.
    const date = (data as { date?: unknown }).date;
    return typeof date === "string" && ISO_DATE.test(date) ? `/family/children/${studentId}#attendance` : `/family/children/${studentId}`;
  }
  // "A guardian reported an absence" opens the class's absence inbox; the API's path has no school, so the caller says which.
  const inbox = CLASS_INBOX.exec(base);
  if (inbox) {
    const slug = context.schoolSlug;
    return slug && SLUG.test(slug) ? `/s/${slug}/classes/${inbox[1]}?tab=absences` : null;
  }
  // Feed links name only the post or story: the school comes from the notification itself (never from the text).
  const post = FEED_POST.exec(base);
  if (post) return context.schoolId && UUID.test(context.schoolId) ? `/feed/posts/${context.schoolId}/${post[1]}` : null;
  const story = FEED_STORY.exec(base);
  if (story) return context.schoolId && UUID.test(context.schoolId) ? `/feed/stories/${context.schoolId}/${story[1]}` : null;
  if (base === FEED_APPROVALS) {
    const slug = context.schoolSlug;
    return slug && SLUG.test(slug) ? `/s/${slug}/feed/approvals` : null;
  }
  return WEB_PATHS[base] ?? safe;
}

/** True when the notification's path needs the school's slug to be resolved (see `schoolSlugOf`). */
export function deepLinkNeedsSchool(data: unknown): boolean {
  if (typeof data !== "object" || data === null) return false;
  const path = (data as { path?: unknown }).path;
  const base = typeof path === "string" ? (path.split(/[?#]/)[0] ?? "") : "";
  return CLASS_INBOX.test(base) || base === FEED_APPROVALS;
}

interface MeSchools {
  memberships?: readonly { schoolId: string; schoolSlug: string }[];
  scopes?: readonly { schoolSlug?: string | null; scope?: { schoolId?: string | null } }[];
}

/** The slug of a school from the signed-in user's memberships, else from their grants at that school. */
export function schoolSlugOf(me: MeSchools, schoolId: string): string | null {
  const member = me.memberships?.find((m) => m.schoolId === schoolId);
  if (member) return member.schoolSlug;
  const grant = me.scopes?.find((s) => s.scope?.schoolId === schoolId && s.schoolSlug);
  return grant?.schoolSlug ?? null;
}

/** Paths the API sends for guardians (see `GuardianNotifications`, `ConsentRequests`) and where the web shows them. */
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const SLUG = /^[a-z0-9][a-z0-9-]*$/;
const CLASS_INBOX = /^\/classes\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/attendance$/i;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const FEED_POST = /^\/feed\/posts\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;
const FEED_STORY = /^\/feed\/stories\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i;
const FEED_APPROVALS = "/feed/approvals";

const WEB_PATHS: Record<string, string> = {
  "/children": "/family",
  "/consents": "/family/consent",
};
