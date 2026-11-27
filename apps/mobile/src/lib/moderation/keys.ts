/**
 * Query keys of reporting (M13c). Everything under this root is memory-only: the only thing cached is the country's safety text,
 * but the root is private anyway so that no report-related data is ever written to TanStack's persisted cache (SQLite). A report
 * itself (reason, details) is never a query: it lives in the sheet's state until it is sent, and nothing is kept afterwards.
 */

export const MODERATION_ROOT = 'moderation';

export const emergencyInfoKey = (country: string) => [MODERATION_ROOT, 'emergency-info', country] as const;

/** Nothing about moderation is persisted: the data lives in memory and is dropped at sign-out. */
export function isPersistableModerationQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== MODERATION_ROOT;
}

/** How long an unused answer stays in memory (the app default is a day). */
export const MODERATION_GC_MS = 5 * 60_000;
