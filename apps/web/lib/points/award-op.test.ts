import { describe, expect, it } from "vitest";
import { createOpTracker, fingerprint, toBody, type AwardIntent } from "./award-op";

const intent = (over: Partial<AwardIntent> = {}): AwardIntent => ({
  categoryId: "cat",
  studentIds: ["s2", "s1"],
  wholeClass: false,
  points: null,
  note: "",
  ...over,
});

function ids() {
  let n = 0;
  return () => `op-${++n}`;
}

describe("createOpTracker", () => {
  it("reuses the clientOpId when the same action is retried after a failure", () => {
    const tracker = createOpTracker(ids());
    const first = tracker.begin(intent());
    const retry = tracker.begin(intent());
    expect(retry.clientOpId).toBe(first.clientOpId);
    expect(retry).toEqual(first);
  });

  it("still reuses it when the same students are chosen in another order", () => {
    const tracker = createOpTracker(ids());
    const a = tracker.begin(intent({ studentIds: ["s1", "s2"] }));
    const b = tracker.begin(intent({ studentIds: ["s2", "s1"] }));
    expect(b.clientOpId).toBe(a.clientOpId);
  });

  it("makes a new id for a different action", () => {
    const tracker = createOpTracker(ids());
    const a = tracker.begin(intent());
    const b = tracker.begin(intent({ categoryId: "other" }));
    const c = tracker.begin(intent({ points: 3, categoryId: "other" }));
    expect(new Set([a.clientOpId, b.clientOpId, c.clientOpId]).size).toBe(3);
  });

  it("makes a new id after a success, even for the same intent", () => {
    const tracker = createOpTracker(ids());
    const a = tracker.begin(intent());
    tracker.succeeded();
    expect(tracker.begin(intent()).clientOpId).not.toBe(a.clientOpId);
  });
});

describe("toBody", () => {
  it("sends studentIds or wholeClass, never both", () => {
    expect(toBody(intent(), "x")).toEqual({ categoryId: "cat", studentIds: ["s2", "s1"], clientOpId: "x" });
    expect(toBody(intent({ wholeClass: true }), "x")).toEqual({ categoryId: "cat", wholeClass: true, clientOpId: "x" });
  });

  it("includes points and a trimmed note only when set", () => {
    expect(toBody(intent({ points: 4, note: "  great  " }), "x")).toMatchObject({ points: 4, note: "great" });
    expect(toBody(intent({ note: "   " }), "x")).not.toHaveProperty("note");
  });
});

describe("fingerprint", () => {
  it("ignores the roster for a whole-class award", () => {
    expect(fingerprint(intent({ wholeClass: true, studentIds: ["a"] }))).toBe(
      fingerprint(intent({ wholeClass: true, studentIds: ["b"] })),
    );
  });
});
