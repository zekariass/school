/**
 * Query keys of the messaging. Everything under this root is memory-only: message bodies, names, member lists and media ids
 * describe children and their families, so none of it may be written to TanStack's persisted cache (SQLite).
 */

export const MESSAGING_ROOT = 'messaging';

export const unreadCountKey = [MESSAGING_ROOT, 'unread'] as const;
export const INBOX_PREFIX = [MESSAGING_ROOT, 'inbox'] as const;
export const inboxKey = (schoolId: string | null, studentId: string | null) =>
  [MESSAGING_ROOT, 'inbox', schoolId ?? 'all', studentId ?? 'all'] as const;
export const conversationKey = (conversationId: string) => [MESSAGING_ROOT, 'conversation', conversationId] as const;
export const membersKey = (conversationId: string) => [MESSAGING_ROOT, 'members', conversationId] as const;
export const messagesKey = (conversationId: string) => [MESSAGING_ROOT, 'messages', conversationId] as const;
export const MESSAGES_PREFIX = [MESSAGING_ROOT, 'messages'] as const;
export const capabilitiesKey = (schoolId: string) => [MESSAGING_ROOT, 'capabilities', schoolId] as const;
export const contactsKey = (schoolId: string, studentId: string, audience: string) =>
  [MESSAGING_ROOT, 'contacts', schoolId, studentId, audience] as const;
export const scheduledKey = [MESSAGING_ROOT, 'scheduled'] as const;
/** A one-off notice for the open conversation (the teacher is outside office hours). Set by the outbox, read by the screen. */
export const afterHoursKey = (conversationId: string) => [MESSAGING_ROOT, 'after-hours', conversationId] as const;
export const groupsKey = (schoolId: string, status: string) => [MESSAGING_ROOT, 'groups', schoolId, status] as const;
export const blocksKey = [MESSAGING_ROOT, 'blocks'] as const;

/** Nothing about messaging is persisted: the pages live in memory and are dropped at sign-out. */
export function isPersistableMessagingQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== MESSAGING_ROOT;
}

/** How long an unused page stays in memory (the app default is a day; messages are not kept that long). */
export const MESSAGING_GC_MS = 3 * 60_000;

export const INBOX_PAGE_SIZE = 25;
/** Pages kept per list: older entries need a pull to refresh / scroll again (bounded memory on cheap phones). */
export const MESSAGING_MAX_PAGES = 6;

/** The cursor of the next page, or `undefined` at the end or at the bound. */
export function nextMessagingPage(last: { nextCursor?: string | undefined }, pagesLoaded: number): string | undefined {
  if (pagesLoaded >= MESSAGING_MAX_PAGES) return undefined;
  return last.nextCursor || undefined;
}
