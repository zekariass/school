import { describe, expect, it } from "vitest";
import { applyReaction, nextReaction, reactionTotal, topReactions } from "./reactions";

const post = { reactionCounts: { LIKE: 2, HEART: 1 } as Record<string, number>, myReaction: undefined as "LIKE" | undefined };

describe("nextReaction", () => {
  it("toggles your own reaction off and switches to another", () => {
    expect(nextReaction(null, "LIKE")).toBe("LIKE");
    expect(nextReaction(undefined, "STAR")).toBe("STAR");
    expect(nextReaction("LIKE", "LIKE")).toBeNull();
    expect(nextReaction("LIKE", "HEART")).toBe("HEART");
  });
});

describe("applyReaction (the optimistic update)", () => {
  it("adds a first reaction", () => {
    const next = applyReaction(post, "STAR");
    expect(next.reactionCounts).toEqual({ LIKE: 2, HEART: 1, STAR: 1 });
    expect(next.myReaction).toBe("STAR");
  });

  it("moves a reaction from one kind to another", () => {
    const mine = { ...post, myReaction: "LIKE" as const };
    const next = applyReaction(mine, "HEART");
    expect(next.reactionCounts).toEqual({ LIKE: 1, HEART: 2 });
    expect(next.myReaction).toBe("HEART");
  });

  it("removes a reaction and drops the empty count", () => {
    const mine = { reactionCounts: { LIKE: 1 }, myReaction: "LIKE" as const };
    const next = applyReaction(mine, null);
    expect(next.reactionCounts).toEqual({});
    expect(next.myReaction).toBeNull();
  });

  it("does not touch anything when nothing changes, and never mutates its input", () => {
    expect(applyReaction(post, null)).toBe(post);
    const before = JSON.stringify(post);
    applyReaction(post, "CLAP");
    expect(JSON.stringify(post)).toBe(before);
  });
});

describe("totals", () => {
  it("sums and orders", () => {
    expect(reactionTotal({ LIKE: 2, STAR: 3 })).toBe(5);
    expect(topReactions({ LIKE: 2, STAR: 3, CLAP: 0 })).toEqual(["STAR", "LIKE"]);
    expect(topReactions({})).toEqual([]);
  });
});
