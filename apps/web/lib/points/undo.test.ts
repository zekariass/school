import { describe, expect, it } from "vitest";
import { addEntry, clock, isUndoable, pruneExpired, removeEntry, reverseTarget, secondsLeft, type UndoEntry } from "./undo";

const NOW = Date.parse("2026-11-25T10:00:00Z");
const entry = (id: string, secondsFromNow: number): UndoEntry => ({
  id,
  target: { kind: "batch", id },
  classId: "c",
  studentIds: ["s"],
  delta: 1,
  className: "3A",
  label: "Kindness",
  undoableUntil: new Date(NOW + secondsFromNow * 1000).toISOString(),
});

describe("secondsLeft", () => {
  it("counts down, rounding up, and stops at zero", () => {
    expect(secondsLeft(entry("a", 120).undoableUntil, NOW)).toBe(120);
    expect(secondsLeft(entry("a", 119.2).undoableUntil, NOW)).toBe(120);
    expect(secondsLeft(entry("a", 1).undoableUntil, NOW)).toBe(1);
    expect(secondsLeft(entry("a", 0).undoableUntil, NOW)).toBe(0);
    expect(secondsLeft(entry("a", -30).undoableUntil, NOW)).toBe(0);
  });

  it("treats an unreadable instant as expired", () => {
    expect(secondsLeft("nope", NOW)).toBe(0);
  });
});

describe("entries", () => {
  it("is undoable only inside the window", () => {
    expect(isUndoable(entry("a", 5), NOW)).toBe(true);
    expect(isUndoable(entry("a", -1), NOW)).toBe(false);
  });

  it("prunes expired entries", () => {
    expect(pruneExpired([entry("a", 5), entry("b", -5)], NOW).map((e) => e.id)).toEqual(["a"]);
  });

  it("adds newest first, replaces a repeated id and keeps five at most", () => {
    let list: UndoEntry[] = [];
    for (let i = 0; i < 7; i++) list = addEntry(list, entry(`e${i}`, 60), NOW);
    expect(list.map((e) => e.id)).toEqual(["e6", "e5", "e4", "e3", "e2"]);
    list = addEntry(list, entry("e3", 90), NOW);
    expect(list.map((e) => e.id)).toEqual(["e3", "e6", "e5", "e4", "e2"]);
  });

  it("does not add an entry whose window is already closed (quick undo off)", () => {
    expect(addEntry([entry("a", 10)], entry("b", 0), NOW).map((e) => e.id)).toEqual(["a"]);
  });

  it("removes by id", () => {
    expect(removeEntry([entry("a", 5), entry("b", 5)], "a").map((e) => e.id)).toEqual(["b"]);
  });
});

describe("reverseTarget", () => {
  it("undoes a multi-student award as a batch", () => {
    expect(reverseTarget({ batchId: "b1", transactions: [{ id: "t1" }, { id: "t2" }] })).toEqual({ kind: "batch", id: "b1" });
  });

  it("undoes a single award by its row (the API sends no batch id for one student)", () => {
    expect(reverseTarget({ transactions: [{ id: "t1" }] })).toEqual({ kind: "transaction", id: "t1" });
  });

  it("has nothing to undo without rows", () => {
    expect(reverseTarget({ transactions: [] })).toBeNull();
    expect(reverseTarget({ transactions: [{ id: "a" }, { id: "b" }] })).toBeNull();
  });
});

describe("clock", () => {
  it("formats m:ss", () => {
    expect(clock(0)).toBe("0:00");
    expect(clock(65)).toBe("1:05");
    expect(clock(-3)).toBe("0:00");
  });
});
