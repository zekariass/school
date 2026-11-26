/** Query keys of the announcements. Everything under this root is memory-only. */

export const ANNOUNCEMENTS_ROOT = 'announcements';

export type InboxFilter = 'ALL' | 'UNREAD' | 'NEEDS_ACKNOWLEDGEMENT';
export type InboxSort = 'NEWEST' | 'UNREAD_FIRST';

export const summaryKey = [ANNOUNCEMENTS_ROOT, 'summary'] as const;
export const inboxKey = (schoolId: string | null, filter: InboxFilter, sort: InboxSort) =>
  [ANNOUNCEMENTS_ROOT, 'inbox', schoolId ?? 'all', filter, sort] as const;
export const INBOX_PREFIX = [ANNOUNCEMENTS_ROOT, 'inbox'] as const;
export const detailKey = (schoolId: string, announcementId: string) => [ANNOUNCEMENTS_ROOT, 'detail', schoolId, announcementId] as const;
export const authoredKey = (schoolId: string, status: string | null, mine: boolean) =>
  [ANNOUNCEMENTS_ROOT, 'authored', schoolId, status ?? 'all', mine ? 'mine' : 'school'] as const;
export const capabilitiesKey = (schoolId: string) => [ANNOUNCEMENTS_ROOT, 'capabilities', schoolId] as const;
export const reportKey = (schoolId: string, announcementId: string) => [ANNOUNCEMENTS_ROOT, 'report', schoolId, announcementId] as const;
export const recipientsKey = (schoolId: string, announcementId: string, filter: string) =>
  [ANNOUNCEMENTS_ROOT, 'recipients', schoolId, announcementId, filter] as const;
export const channelsKey = [ANNOUNCEMENTS_ROOT, 'channels'] as const;
export const audienceChoicesKey = (schoolId: string, what: string, q = '') => [ANNOUNCEMENTS_ROOT, 'audience', schoolId, what, q] as const;
export const anywhereKey = (announcementId: string, schools: string) => [ANNOUNCEMENTS_ROOT, 'anywhere', announcementId, schools] as const;

/**
 * Nothing about announcements is written to TanStack's persisted cache (SQLite): their texts, the lists of who read what and the
 * names of recipients are personal data. They live in memory only and are dropped at sign-out.
 */
export function isPersistableAnnouncementQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== ANNOUNCEMENTS_ROOT;
}

/** How long an unused page stays in memory (the app default is a day). */
export const ANNOUNCEMENTS_GC_MS = 5 * 60_000;

export const PAGE_SIZE = 20;
/** Pages kept per list: older entries need a pull to refresh (bounded memory on cheap phones). */
export const MAX_PAGES = 8;

/** The cursor of the next page, or `undefined` at the end or at the bound. */
export function nextPage(last: { nextCursor?: string | undefined }, pagesLoaded: number): string | undefined {
  if (pagesLoaded >= MAX_PAGES) return undefined;
  return last.nextCursor || undefined;
}
