export const REACTIONS = ["LIKE", "HEART", "CLAP", "STAR"] as const;
export type ReactionName = (typeof REACTIONS)[number];

export interface Reactable {
  reactionCounts: Record<string, number>;
  myReaction?: ReactionName | null;
}

/** What tapping `clicked` does: tapping your current reaction takes it back. */
export function nextReaction(current: ReactionName | null | undefined, clicked: ReactionName): ReactionName | null {
  return current === clicked ? null : clicked;
}

/** The post as it looks after the caller's reaction changes to `next` (null removes it): the optimistic update. */
export function applyReaction<T extends Reactable>(post: T, next: ReactionName | null): T {
  const counts = { ...post.reactionCounts };
  const previous = post.myReaction ?? null;
  if (previous === next) return post;
  if (previous) {
    const left = (counts[previous] ?? 0) - 1;
    if (left > 0) counts[previous] = left;
    else delete counts[previous];
  }
  if (next) counts[next] = (counts[next] ?? 0) + 1;
  return { ...post, reactionCounts: counts, myReaction: next };
}

/** Total reactions on a post. */
export function reactionTotal(counts: Record<string, number>): number {
  return Object.values(counts).reduce((sum, n) => sum + n, 0);
}

/** The reactions that have at least one, most used first (ties in the fixed order). */
export function topReactions(counts: Record<string, number>): ReactionName[] {
  return REACTIONS.filter((r) => (counts[r] ?? 0) > 0).sort((a, b) => (counts[b] ?? 0) - (counts[a] ?? 0));
}
