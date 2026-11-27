/** Query keys of the portfolio. Everything under this root is memory-only (children's own work, photos and notes). */

export const PORTFOLIO_ROOT = 'portfolio';

export const capabilitiesKey = (schoolId: string) => [PORTFOLIO_ROOT, 'capabilities', schoolId] as const;
export const mineKey = (schoolId: string, status: string | null) => [PORTFOLIO_ROOT, 'mine', schoolId, status ?? 'all'] as const;
export const queueKey = (schoolId: string, classId: string) => [PORTFOLIO_ROOT, 'queue', schoolId, classId] as const;
/** A guardian's combined view of the children (or of one child). */
export const childrenKey = (studentId: string | null) => [PORTFOLIO_ROOT, 'children', studentId ?? 'all'] as const;
export const itemKey = (schoolId: string, itemId: string) => [PORTFOLIO_ROOT, 'item', schoolId, itemId] as const;
export const commentsKey = (schoolId: string, itemId: string) => [PORTFOLIO_ROOT, 'comments', schoolId, itemId] as const;
export const historyKey = (schoolId: string, itemId: string) => [PORTFOLIO_ROOT, 'history', schoolId, itemId] as const;

/**
 * Nothing of the portfolio is written to TanStack's persisted cache (SQLite): titles, reflections, review notes, comments,
 * photos' ids and tagged children describe children's own work. It lives in memory only and is dropped at sign-out; opened
 * without a connection, the screens say that they need one instead of showing anything old.
 */
export function isPersistablePortfolioQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== PORTFOLIO_ROOT;
}

/** How long an unused portfolio page stays in memory (the app default is a day; the portfolio is not kept that long). */
export const PORTFOLIO_GC_MS = 3 * 60_000;

/** Items per page, and pages kept per list (bounded memory on cheap phones; older items need a pull to refresh). */
export const PORTFOLIO_PAGE_SIZE = 12;
export const PORTFOLIO_MAX_PAGES = 6;

/** The cursor of the next page, or `undefined` at the end or at the bound. */
export function nextPortfolioPage(last: { nextCursor?: string | undefined }, pagesLoaded: number): string | undefined {
  if (pagesLoaded >= PORTFOLIO_MAX_PAGES) return undefined;
  return last.nextCursor || undefined;
}
