import { ApiRequestError } from '@/lib/api-error';

import { AUTHOR_ROLE_LABEL, HISTORY_FALLBACK, HISTORY_LABEL } from './labels';
import type { PortfolioComment, PortfolioHistoryEntry, PortfolioItem, PortfolioItemType, PortfolioStatus, PortfolioVisibility, ReviewDecision } from './types';

/**
 * What a screen shows, decided from the payload alone. The API already decided who sees what (status, effective visibility,
 * `capabilities`, `mediaWithheld`, withheld files): the app only words it and stays honest when something is missing.
 */

// --- visibility ----------------------------------------------------------------------------------

export const VISIBILITY_ORDER: readonly PortfolioVisibility[] = ['STUDENT_TEACHER', 'GUARDIANS', 'CLASS', 'SCHOOL'];

const rank = (visibility: PortfolioVisibility) => VISIBILITY_ORDER.indexOf(visibility);

/** The audiences a student may pick: up to the school's cap (`capabilities.maxVisibility`; the API lowers anything above it). */
export function visibilityChoices(max: PortfolioVisibility): PortfolioVisibility[] {
  return VISIBILITY_ORDER.filter((visibility) => rank(visibility) <= rank(max));
}

/** The first choice: the child's own family, or less when the school allows no more. */
export function defaultVisibility(max: PortfolioVisibility): PortfolioVisibility {
  return rank(max) < rank('GUARDIANS') ? max : 'GUARDIANS';
}

/** A reviewer may lower the audience when approving, never raise it: the current one and the narrower ones. */
export function lowerVisibilities(current: PortfolioVisibility): PortfolioVisibility[] {
  return VISIBILITY_ORDER.filter((visibility) => rank(visibility) <= rank(current));
}

/** True when the school's cap makes the item narrower than the author chose (worth saying so). */
export function isLoweredByCap(item: Pick<PortfolioItem, 'visibility' | 'effectiveVisibility'>): boolean {
  return rank(item.effectiveVisibility) < rank(item.visibility);
}

// --- files withheld --------------------------------------------------------------------------------

/**
 * `hidden`: the viewer gets no pictures at all (consent is missing: the item shows, its files do not).
 * `warning`: the student or a reviewer still sees the files, but the API says they are withheld from others.
 */
export type WithheldNotice = 'hidden' | 'warning' | null;

export function withheldNotice(item: Pick<PortfolioItem, 'mediaWithheld' | 'media'>): WithheldNotice {
  if (!item.mediaWithheld) return null;
  return item.media.length === 0 ? 'hidden' : 'warning';
}

// --- filters and decisions -------------------------------------------------------------------------

export type StatusFilter = PortfolioStatus | 'ALL';

/** The filter chips of the student's own list. */
export const STATUS_FILTERS: readonly StatusFilter[] = ['ALL', 'DRAFT', 'SUBMITTED', 'CHANGES_REQUESTED', 'APPROVED', 'ARCHIVED'];

/** Asking for changes or rejecting needs a note the student can read; approving does not. */
export function noteRequired(decision: ReviewDecision): boolean {
  return decision !== 'APPROVE';
}

// --- text limits (the API checks them again) -----------------------------------------------------------

export const LIMITS = { title: 120, description: 1000, reflection: 1000, text: 5000, note: 500, comment: 500, reason: 200 } as const;

export type CommentProblem = 'empty' | 'too_long';

export function commentProblem(text: string): CommentProblem | null {
  const trimmed = text.trim();
  if (!trimmed) return 'empty';
  return [...trimmed].length > LIMITS.comment ? 'too_long' : null;
}

// --- who wrote it ----------------------------------------------------------------------------------

export type AuthorLabel = { kind: 'name'; name: string } | { kind: 'role'; labelKey: (typeof AUTHOR_ROLE_LABEL)[keyof typeof AUTHOR_ROLE_LABEL] };

/** Reviewers appear by name; guardians and students by role only (the API sends no name for them): never a guess. */
export function authorLabel(author: Pick<PortfolioComment, 'authorName' | 'authorRole'>): AuthorLabel {
  const name = author.authorName?.trim();
  return name ? { kind: 'name', name } : { kind: 'role', labelKey: AUTHOR_ROLE_LABEL[author.authorRole] };
}

/** A hidden comment stays as a tombstone: its text is shown only to its author and the reviewers (the API leaves it out). */
export function isTombstone(comment: Pick<PortfolioComment, 'status'>): boolean {
  return comment.status === 'HIDDEN';
}

/** The catalog key of a history row's action, or a neutral one for a code this app does not know. */
export function historyLabelKey(entry: Pick<PortfolioHistoryEntry, 'action'>): (typeof HISTORY_LABEL)[keyof typeof HISTORY_LABEL] | typeof HISTORY_FALLBACK {
  return (HISTORY_LABEL as Record<string, (typeof HISTORY_LABEL)[keyof typeof HISTORY_LABEL]>)[entry.action] ?? HISTORY_FALLBACK;
}

// --- what kind of item can be made on a phone ------------------------------------------------------------

/**
 * The item types this phone app can create. A drawing is a photo of paper (no in-app canvas) and a scan is a photo of a page
 * (the camera with a crop); PDF documents need a document picker the app does not ship, so DOCUMENT is not offered. The list
 * is cut down to what the API says the school supports (`capabilities.itemTypes`).
 */
export const PHONE_ITEM_TYPES: readonly PortfolioItemType[] = ['PHOTO', 'SCAN', 'DRAWING', 'TEXT'];

export function creatableTypes(apiTypes: readonly string[]): PortfolioItemType[] {
  return PHONE_ITEM_TYPES.filter((type) => apiTypes.includes(type));
}

export function takesPhotos(type: PortfolioItemType): boolean {
  return type === 'PHOTO' || type === 'DRAWING' || type === 'SCAN';
}

// --- the connection ---------------------------------------------------------------------------------

/** True when the call failed without an answer from the server (offline, timeout, DNS): the screen says it needs a connection. */
export function needsConnection(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === undefined;
}

export type ListState = 'loading' | 'offline' | 'error' | 'empty' | 'ready';

/**
 * One state for a list screen. Nothing is persisted, so a phone opened offline has nothing to show: the query is paused (or its
 * first fetch failed without an answer) and the screen says "needs a connection" instead of an empty list or an old copy.
 */
export function listState(query: {
  isPending: boolean;
  isError: boolean;
  error: unknown;
  fetchStatus: 'fetching' | 'paused' | 'idle';
  hasItems: boolean;
}): ListState {
  if (query.hasItems) return 'ready';
  if (query.isPending) return query.fetchStatus === 'paused' ? 'offline' : 'loading';
  if (query.isError) return needsConnection(query.error) ? 'offline' : 'error';
  return 'empty';
}
