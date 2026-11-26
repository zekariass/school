/** Query keys of the feed. Everything under this root is memory-only. */

export const FEED_ROOT = 'feed';

export const classFeedKey = (schoolId: string, classId: string) => [FEED_ROOT, 'class', schoolId, classId] as const;
export const schoolFeedKey = (schoolId: string) => [FEED_ROOT, 'school', schoolId] as const;
export const homeFeedKey = (studentId: string | null) => [FEED_ROOT, 'home', studentId ?? 'all'] as const;
export const myPostsKey = (schoolId: string, status: string | null, classId: string | null) =>
  [FEED_ROOT, 'mine', schoolId, status ?? 'all', classId ?? 'all'] as const;
export const pendingKey = (schoolId: string, classId: string | null) => [FEED_ROOT, 'pending', schoolId, classId ?? 'all'] as const;
export const postKey = (schoolId: string, postId: string) => [FEED_ROOT, 'post', schoolId, postId] as const;
export const commentsKey = (schoolId: string, postId: string) => [FEED_ROOT, 'comments', schoolId, postId] as const;
export const capabilitiesKey = (schoolId: string, classId: string | null) => [FEED_ROOT, 'capabilities', schoolId, classId ?? 'school'] as const;
export const classStoriesKey = (schoolId: string, classId: string) => [FEED_ROOT, 'stories', 'class', schoolId, classId] as const;
export const schoolStoriesKey = (schoolId: string) => [FEED_ROOT, 'stories', 'school', schoolId] as const;
export const homeStoriesKey = (studentId: string | null) => [FEED_ROOT, 'stories', 'home', studentId ?? 'all'] as const;
export const storyKey = (schoolId: string, storyId: string) => [FEED_ROOT, 'story', schoolId, storyId] as const;

/**
 * Nothing in the feed is written to TanStack's persisted cache (SQLite): posts, comments, photos' ids, tagged children's
 * names and stories describe children's school life. The feed lives in memory only and is dropped at sign-out.
 */
export function isPersistableFeedQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== FEED_ROOT;
}

/** How long an unused feed page stays in memory (the app default is a day; the feed is not kept that long). */
export const FEED_GC_MS = 5 * 60_000;

/** Posts per page, and pages kept per feed: older posts need a pull to refresh (bounded memory on cheap phones). */
export const FEED_PAGE_SIZE = 15;
export const FEED_MAX_PAGES = 8;

/** The cursor of the next page, or `undefined` at the end or at the bound. */
export function nextFeedPage(last: { nextCursor?: string | undefined }, pagesLoaded: number): string | undefined {
  if (pagesLoaded >= FEED_MAX_PAGES) return undefined;
  return last.nextCursor || undefined;
}
