import type { InboxFilter, InboxSort } from './keys';
import type { AnnouncementCard, AnnouncementStatus, AnnouncementView } from './types';

/** The query of `GET /me/announcements` for a filter and a sort (`ALL` and `NEWEST` are the defaults and are left out). */
export function inboxQueryParams(input: { schoolId: string | null; filter: InboxFilter; sort: InboxSort }): {
  schoolId?: string;
  filter?: 'UNREAD' | 'NEEDS_ACKNOWLEDGEMENT';
  sort?: 'UNREAD_FIRST';
} {
  return {
    ...(input.schoolId ? { schoolId: input.schoolId } : {}),
    ...(input.filter !== 'ALL' ? { filter: input.filter } : {}),
    ...(input.sort === 'UNREAD_FIRST' ? { sort: 'UNREAD_FIRST' as const } : {}),
  };
}

/**
 * "Unread" for a card of the inbox (the recipient snapshot, so everything in it was addressed to the caller). A withdrawn
 * announcement is not a thing to read any more: no badge.
 */
export function isUnreadCard(card: Pick<AnnouncementCard, 'readAt' | 'status'>): boolean {
  return card.status === 'PUBLISHED' && !card.readAt;
}

/** A card that still waits for the caller's acknowledgement. */
export function needsAcknowledgementCard(card: Pick<AnnouncementCard, 'requiresAcknowledgement' | 'acknowledgedAt' | 'status'>): boolean {
  return card.status === 'PUBLISHED' && card.requiresAcknowledgement && !card.acknowledgedAt;
}

/**
 * The "new" badge of an opened announcement: only people who were addressed (in the recipient snapshot) get one. A person who
 * finds it through the school's list (a guardian linked later) was never notified and has nothing "new" to catch up on.
 */
export function showNewBadge(view: Pick<AnnouncementView, 'mine' | 'status'>): boolean {
  return view.status === 'PUBLISHED' && view.mine.recipient && !view.mine.read;
}

/** What the bell-like badge of the entry point shows: the larger of "unread" and "needs acknowledgement" (they overlap). */
export function badgeCount(summary: { unread: number; needingAcknowledgement: number } | undefined): number {
  if (!summary) return 0;
  return Math.max(summary.unread, summary.needingAcknowledgement, 0);
}

/** True when no loaded page holds an item (the empty state, as opposed to "still loading"). */
export function isInboxEmpty(pages: { items: unknown[] }[] | undefined): boolean {
  return !pages || pages.every((page) => page.items.length === 0);
}

/** The statuses an author filters their own list by (a recipient never sees DRAFT or SCHEDULED). */
export const AUTHOR_STATUS_FILTERS: readonly (AnnouncementStatus | 'ALL')[] = ['ALL', 'DRAFT', 'SCHEDULED', 'PUBLISHED', 'WITHDRAWN'];
