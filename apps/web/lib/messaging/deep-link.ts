import { isUuid } from "@/lib/feed/ids";

const SLUG = /^[a-z0-9][a-z0-9-]*$/;

/**
 * True when a notification is about the conversation the person is looking at right now (`pathname` is the browser's, with or
 * without the locale and the workspace prefix). The live toast is then noise: the message is already on screen.
 */
export function isAboutOpenConversation(data: unknown, pathname: string): boolean {
  if (typeof data !== "object" || data === null) return false;
  const path = (data as { path?: unknown }).path;
  if (typeof path !== "string") return false;
  const match = /^\/messages\/([^/?#]+)/.exec(path);
  if (!match || !isUuid(match[1])) return false;
  return pathname.toLowerCase().endsWith(`/messages/${match[1]!.toLowerCase()}`);
}

/**
 * Where a messaging notification opens. The API sends relative paths (`NEW_MESSAGE`, `NEW_MESSAGE_QUIET`,
 * `MESSAGE_OBSERVER_ADDED`, `MESSAGE_REPORTED`, `MESSAGE_SCHEDULE_BLOCKED`: `/messages/{conversationId}`;
 * `GROUP_APPROVAL_REQUESTED`: `/messages/groups`; reviewers' `MESSAGE_REPORTED`: `/s/{slug}/messaging/review`).
 * Ids must be UUIDs and slugs well-formed, otherwise the link falls back to the inbox. Returns null for paths that are not
 * messaging paths. The result has no locale: the localized router adds it.
 */
export function messagingDeepLink(base: string): string | null {
  if (base === "/messages") return "/messages";
  if (base === "/messages/groups") return "/messages/groups";
  const conversation = /^\/messages\/([^/]+)$/.exec(base);
  if (conversation) return isUuid(conversation[1]) ? `/messages/${conversation[1]}` : "/messages";
  const review = /^\/s\/([^/]+)\/messaging\/review$/.exec(base);
  if (review) return SLUG.test(review[1]!) ? `/s/${review[1]}/messages/review` : null;
  return null;
}
