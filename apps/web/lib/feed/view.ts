import type { FeedAuthor, Post, PostStatus, Story } from "./queries";

/** The label shown for an author: the name when the API sent one, else only the role (never a guess). */
export type AuthorLabel = { kind: "name"; name: string; role: FeedAuthor["role"] } | { kind: "role"; role: FeedAuthor["role"] };

export function authorLabel(author: FeedAuthor): AuthorLabel {
  const name = author.displayName?.trim();
  return name ? { kind: "name", name, role: author.role } : { kind: "role", role: author.role };
}

/** Statuses the author sees as work in progress. */
export const AUTHOR_STATUSES: readonly PostStatus[] = ["DRAFT", "SCHEDULED", "PENDING_APPROVAL", "REJECTED", "PUBLISHED", "ARCHIVED", "HIDDEN"];

/** The tabs of "my posts" (a status each; the API takes one status at a time). */
export const MY_POST_TABS = ["DRAFT", "SCHEDULED", "PENDING_APPROVAL", "REJECTED", "PUBLISHED", "ARCHIVED"] as const satisfies readonly PostStatus[];

/** True when the post's photos are hidden from this viewer (consent not granted) and the API sent none. */
export function isWithheldPlaceholder(post: Pick<Post, "mediaWithheld" | "media">): boolean {
  return !!post.mediaWithheld && post.media.length === 0;
}

/** True for the author or a moderator, who still see photos that are withheld from families (a warning, not a gate). */
export function isWithheldWarning(post: Pick<Post, "mediaWithheld" | "media">): boolean {
  return !!post.mediaWithheld && post.media.length > 0;
}

/** Whether a "seen by" count may be shown (only when the API sent it). */
export function hasSeenCount(post: Pick<Post, "seenCount"> | Pick<Story, "seenCount">): boolean {
  return typeof post.seenCount === "number";
}

/** The image grid layout class for n photos (1 large, 2 side by side, 3+ a grid with the first large). */
export function gridShape(count: number): "one" | "two" | "many" {
  return count <= 1 ? "one" : count === 2 ? "two" : "many";
}

/** Photos shown in a card; the rest is "+n". */
export const GRID_LIMIT = 4;

export function gridPhotos<T>(items: readonly T[]): { shown: T[]; extra: number } {
  return { shown: items.slice(0, GRID_LIMIT), extra: Math.max(0, items.length - GRID_LIMIT) };
}

/** Which actions the overflow menu offers, from the post's status and the API's `rights` (the API stays the authority). */
export interface PostMenu {
  edit: boolean;
  publish: boolean;
  schedule: boolean;
  unschedule: boolean;
  pin: boolean;
  unpin: boolean;
  archive: boolean;
  hide: boolean;
  unhide: boolean;
  remove: boolean;
  report: boolean;
}

export function postMenu(post: Pick<Post, "status" | "pinned" | "rights" | "contribution">): PostMenu {
  const r = post.rights;
  const published = post.status === "PUBLISHED";
  return {
    edit: !!r.canEdit && post.status !== "REJECTED" && post.status !== "HIDDEN",
    publish: !!r.canPublish && (post.status === "DRAFT" || post.status === "SCHEDULED" || post.status === "ARCHIVED"),
    schedule: !!r.canEdit && (post.status === "DRAFT" || post.status === "SCHEDULED"),
    unschedule: !!r.canEdit && post.status === "SCHEDULED",
    pin: !!r.canPin && published && !post.pinned,
    unpin: !!r.canPin && published && !!post.pinned,
    archive: published && (!!r.canEdit || !!r.canModerate),
    hide: !!r.canModerate && published,
    unhide: !!r.canModerate && post.status === "HIDDEN",
    remove: !!r.canDelete,
    report: published && !r.canEdit && !r.canModerate,
  };
}

export function hasAnyAction(menu: PostMenu): boolean {
  return Object.values(menu).some(Boolean);
}
