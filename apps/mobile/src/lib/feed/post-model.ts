import { canReportPost } from '@/lib/moderation/visibility';

import type { FeedAuthor, PostView } from './types';

/**
 * What a card shows, decided from the payload alone. The API already left out what the viewer may not know (a guardian's
 * or a child's name, tags of other families' children); the app only has to stay honest when something is missing.
 */

export type AuthorLabel = { kind: 'name'; name: string } | { kind: 'role'; role: FeedAuthor['role'] };

/** The author's name when the API sent one, otherwise the role label only ("Family", "Student"): never a guess. */
export function authorLabel(author: Pick<FeedAuthor, 'displayName' | 'role'>): AuthorLabel {
  const name = author.displayName?.trim();
  return name ? { kind: 'name', name } : { kind: 'role', role: author.role };
}

export type Age = { unit: 'now' } | { unit: 'minutes'; value: number } | { unit: 'hours'; value: number } | { unit: 'date' };

/** Coarse "how long ago" for a card; older than a day falls back to the date. */
export function ageOf(iso: string | null | undefined, nowMs: number): Age {
  const then = iso ? Date.parse(iso) : Number.NaN;
  if (!Number.isFinite(then)) return { unit: 'date' };
  const delta = Math.max(0, nowMs - then);
  if (delta < 60_000) return { unit: 'now' };
  if (delta < 3_600_000) return { unit: 'minutes', value: Math.floor(delta / 60_000) };
  if (delta < 86_400_000) return { unit: 'hours', value: Math.floor(delta / 3_600_000) };
  return { unit: 'date' };
}

/** The instant a card is dated by: when it went live, else when it was made. */
export function postInstant(post: Pick<PostView, 'publishedAt' | 'createdAt'>): string {
  return post.publishedAt ?? post.createdAt;
}

export type OverflowAction =
  | 'edit'
  | 'publish'
  | 'unschedule'
  | 'pin'
  | 'unpin'
  | 'archive'
  | 'hide'
  | 'unhide'
  | 'report'
  | 'delete';

/**
 * The menu of a post, from its `rights` and status (the API stays the authority: a 403/404/409 is shown inline).
 * Nothing here is derived from the role of the viewer.
 */
export function overflowActions(post: Pick<PostView, 'rights' | 'status' | 'pinned'>): OverflowAction[] {
  const { rights, status } = post;
  const actions: OverflowAction[] = [];
  if (rights.canEdit) actions.push('edit');
  if (rights.canPublish) actions.push('publish');
  if (rights.canEdit && status === 'SCHEDULED') actions.push('unschedule');
  if (rights.canPin) actions.push(post.pinned ? 'unpin' : 'pin');
  if (rights.canDelete && status === 'PUBLISHED') actions.push('archive');
  if (rights.canModerate && status === 'PUBLISHED') actions.push('hide');
  if (rights.canModerate && status === 'HIDDEN') actions.push('unhide');
  if (canReportPost(post)) actions.push('report');
  if (rights.canDelete) actions.push('delete');
  return actions;
}

/** Whether the viewer wrote the post (their id is only sent to them, and to people who may know the author). */
export function isMine(post: Pick<PostView, 'author'>, myUserId: string | undefined): boolean {
  return !!myUserId && post.author.userId === myUserId;
}

/** Posts in these states are not public: a badge tells the author where the post stands. */
export function statusBadge(post: Pick<PostView, 'status' | 'contribution'>): PostView['status'] | null {
  return post.status === 'PUBLISHED' ? null : post.status;
}

/** True when this card hides its photos from the viewer (consent not granted right now): the text stays. */
export function photosHidden(post: Pick<PostView, 'mediaWithheld' | 'media'>): boolean {
  return !!post.mediaWithheld && post.media.length === 0;
}

/** The author or a moderator still sees the photos of a withheld post, with a warning that nobody else does. */
export function photosWarning(post: Pick<PostView, 'mediaWithheld' | 'media'>): boolean {
  return !!post.mediaWithheld && post.media.length > 0;
}

/** Whether "See translation" is worth offering: the feature is on, there is text, and it is not already in the app language. */
export function canOfferTranslation(post: Pick<PostView, 'body' | 'bodyLanguageCode'>, enabled: boolean, uiLanguage: string): boolean {
  if (!enabled || !post.body?.trim()) return false;
  const source = post.bodyLanguageCode?.toLowerCase().split('-')[0];
  return !source || source !== uiLanguage.toLowerCase().split('-')[0];
}
