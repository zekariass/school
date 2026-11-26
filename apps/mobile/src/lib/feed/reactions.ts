import type { PostView, ReactionType } from './types';

type Counts = Record<string, number>;

/**
 * The post as it will look after the viewer's reaction becomes `next` (`null` removes it): one reaction per person, so the
 * previous one loses a count and the new one gains it. Used for the optimistic update; the answer of the API replaces it.
 */
export function applyReaction<T extends Pick<PostView, 'reactionCounts' | 'myReaction'>>(post: T, next: ReactionType | null): T {
  const previous = post.myReaction ?? null;
  if (previous === next) return post;
  const counts: Counts = { ...post.reactionCounts };
  if (previous) counts[previous] = Math.max(0, (counts[previous] ?? 0) - 1);
  if (next) counts[next] = (counts[next] ?? 0) + 1;
  for (const key of Object.keys(counts)) if (counts[key] === 0) delete counts[key];
  const result = { ...post, reactionCounts: counts } as T;
  if (next) (result as { myReaction?: ReactionType }).myReaction = next;
  else delete (result as { myReaction?: ReactionType }).myReaction;
  return result;
}

/** What tapping `tapped` does: tapping your own reaction takes it back. */
export function nextReaction(current: ReactionType | null | undefined, tapped: ReactionType): ReactionType | null {
  return current === tapped ? null : tapped;
}

/** All reactions on a post, as a number. */
export function totalReactions(counts: Counts | undefined): number {
  return Object.values(counts ?? {}).reduce((sum, value) => sum + value, 0);
}

/** The reaction counts the API answered with, applied to the post (the server is the truth after the optimistic step). */
export function withSummary<T extends Pick<PostView, 'reactionCounts' | 'myReaction'>>(
  post: T,
  summary: { counts: Counts; mine?: ReactionType | undefined },
): T {
  const result = { ...post, reactionCounts: summary.counts } as T;
  if (summary.mine) (result as { myReaction?: ReactionType }).myReaction = summary.mine;
  else delete (result as { myReaction?: ReactionType }).myReaction;
  return result;
}
