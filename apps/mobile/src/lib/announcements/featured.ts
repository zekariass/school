import type { AnnouncementCard } from './types';

/** How many of the newest cards are asked for: enough to step over a withdrawn one. */
export const FEATURED_LOOKAHEAD = 5;

/**
 * The announcement the feed shows on top: the newest one the API lists for the person that is still in force (published; a
 * withdrawn one is not announced any more). `null` when there is none, and then the feed shows no card.
 */
export function pickFeatured<T extends Pick<AnnouncementCard, 'status'>>(cards: readonly T[] | undefined): T | null {
  return cards?.find((card) => card.status === 'PUBLISHED') ?? null;
}
