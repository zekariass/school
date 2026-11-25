import { describe, expect, it } from "vitest";

import {
  EMPTY_PICKER,
  addTime,
  applyDelta,
  countdown,
  elapsedMs,
  formatClock,
  isFinished,
  makeGroups,
  pause,
  pickNext,
  remainingMs,
  reset,
  seededRng,
  shuffle,
  start,
  stopwatch,
} from "./index";

const ids = ["a", "b", "c", "d", "e", "f", "g"];

describe("shuffle", () => {
  it("keeps every item and does not change the input", () => {
    const input = [...ids];
    const out = shuffle(input, seededRng(1));
    expect(input).toEqual(ids);
    expect([...out].sort()).toEqual(ids);
  });
  it("is deterministic for a seed", () => {
    expect(shuffle(ids, seededRng(42))).toEqual(shuffle(ids, seededRng(42)));
  });
});

describe("pickNext", () => {
  it("returns null for an empty class", () => {
    expect(pickNext([], EMPTY_PICKER).id).toBeNull();
  });
  it("picks everyone once before anyone twice", () => {
    const rng = seededRng(7);
    let state = EMPTY_PICKER;
    const seen: string[] = [];
    for (let i = 0; i < ids.length; i++) {
      const r = pickNext(ids, state, rng);
      expect(r.newRound).toBe(false);
      seen.push(r.id!);
      state = r.state;
    }
    expect([...seen].sort()).toEqual(ids);
    const next = pickNext(ids, state, rng);
    expect(next.newRound).toBe(true);
    expect(next.id).not.toBe(seen[seen.length - 1]);
  });
  it("forgets students who left and picks new ones at once", () => {
    const first = pickNext(["a", "b"], EMPTY_PICKER, seededRng(1));
    const next = pickNext(["b", "c"], first.state, seededRng(1));
    expect(["b", "c"]).toContain(next.id);
    expect(next.state.picked).not.toContain("a");
  });
  it("works with a single student", () => {
    const one = pickNext(["a"], EMPTY_PICKER);
    expect(pickNext(["a"], one.state).id).toBe("a");
  });
});

describe("makeGroups", () => {
  it("balances sizes within one student", () => {
    const groups = makeGroups(ids, { groupCount: 3 }, seededRng(3));
    expect(groups.map((g) => g.length).sort()).toEqual([2, 2, 3]);
    expect(groups.flat().sort()).toEqual(ids);
  });
  it("derives the count from a size", () => {
    expect(makeGroups(ids, { groupSize: 3 }, seededRng(3))).toHaveLength(3);
    expect(makeGroups(ids, { groupSize: 100 })).toHaveLength(1);
  });
  it("never makes more groups than students and rejects bad specs", () => {
    expect(makeGroups(["a", "b"], { groupCount: 9 })).toHaveLength(2);
    expect(makeGroups(ids, { groupCount: 0 })).toEqual([]);
    expect(makeGroups(ids, { groupSize: 0 })).toEqual([]);
    expect(makeGroups([], { groupCount: 2 })).toEqual([]);
    expect(makeGroups(ids, { groupCount: Number.NaN })).toEqual([]);
  });
});

describe("timer", () => {
  it("counts down while running and stops at zero", () => {
    let t = start(countdown(60_000), 1_000);
    expect(remainingMs(t, 11_000)).toBe(50_000);
    expect(isFinished(t, 61_000)).toBe(true);
    expect(remainingMs(t, 99_000)).toBe(0);
    t = pause(t, 11_000);
    expect(remainingMs(t, 500_000)).toBe(50_000);
  });
  it("resumes from where it paused and resets", () => {
    let t = start(stopwatch(), 0);
    t = pause(t, 5_000);
    t = start(t, 10_000);
    expect(elapsedMs(t, 12_000)).toBe(7_000);
    expect(remainingMs(t, 12_000)).toBeNull();
    expect(elapsedMs(reset(t), 12_000)).toBe(0);
  });
  it("does not restart a finished countdown and can add time", () => {
    const done = start(countdown(1_000), 0);
    expect(start(done, 5_000)).toBe(done);
    const t = addTime(pause(done, 400), 60_000, 400);
    expect(remainingMs(t, 400)).toBe(60_600);
    expect(addTime(pause(done, 400), -999_999, 400).durationMs).toBe(400);
  });
  it("formats clocks", () => {
    expect(formatClock(0)).toBe("0:00");
    expect(formatClock(59_999)).toBe("0:59");
    expect(formatClock(59_001, true)).toBe("1:00");
    expect(formatClock(3_725_000)).toBe("1:02:05");
    expect(formatClock(-5)).toBe("0:00");
  });
});

describe("applyDelta", () => {
  it("adds without mutating and supports undo", () => {
    const before = { a: 2 };
    const after = applyDelta(before, ["a", "b"], 3);
    expect(before).toEqual({ a: 2 });
    expect(after).toEqual({ a: 5, b: 3 });
    expect(applyDelta(after, ["a", "b"], -3)).toEqual({ a: 2, b: 0 });
  });
});
