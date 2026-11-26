import { describe, expect, it } from "vitest";
import { STORY_PHOTO_MS, STORY_TEXT_MS, inViewingOrder, progressOf, startIndex, stepBack, stepForward, storyDuration, timeLeft } from "./story-timing";

describe("progress", () => {
  it("fills from 0 to 1 and never beyond", () => {
    expect(progressOf(0, 6000)).toBe(0);
    expect(progressOf(3000, 6000)).toBe(0.5);
    expect(progressOf(9000, 6000)).toBe(1);
    expect(progressOf(-5, 6000)).toBe(0);
    expect(progressOf(10, 0)).toBe(1);
  });

  it("gives a caption-only story more time than a photo", () => {
    expect(storyDuration({ media: { mediaId: "m" } })).toBe(STORY_PHOTO_MS);
    expect(storyDuration({})).toBe(STORY_TEXT_MS);
    expect(STORY_TEXT_MS).toBeGreaterThan(STORY_PHOTO_MS);
  });
});

describe("moving between stories", () => {
  it("steps forward and finishes after the last one", () => {
    expect(stepForward(0, 3)).toEqual({ done: false, index: 1 });
    expect(stepForward(2, 3)).toEqual({ done: true });
    expect(stepForward(0, 1)).toEqual({ done: true });
  });

  it("steps back but stays on the first", () => {
    expect(stepBack(2)).toBe(1);
    expect(stepBack(0)).toBe(0);
  });

  it("starts at the first unseen story, else the first", () => {
    expect(startIndex([{ viewedByMe: true }, { viewedByMe: false }, {}])).toBe(1);
    expect(startIndex([{ viewedByMe: true }, { viewedByMe: true }])).toBe(0);
    expect(startIndex([])).toBe(0);
  });

  it("tells stories oldest first", () => {
    const stories = [{ id: "b", publishedAt: "2026-11-26T09:00:00Z" }, { id: "a", publishedAt: "2026-11-26T07:00:00Z" }];
    expect(inViewingOrder(stories).map((s) => s.id)).toEqual(["a", "b"]);
    expect(stories[0]?.id).toBe("b");
  });
});

describe("time left (24 hour expiry)", () => {
  const now = Date.parse("2026-11-26T10:00:00Z");

  it("counts hours and minutes, rounding up the last partial minute", () => {
    expect(timeLeft("2026-11-27T10:00:00Z", now)).toEqual({ expired: false, hours: 24, minutes: 0 });
    expect(timeLeft("2026-11-26T13:20:00Z", now)).toEqual({ expired: false, hours: 3, minutes: 20 });
    expect(timeLeft("2026-11-26T10:00:30Z", now)).toEqual({ expired: false, hours: 0, minutes: 1 });
  });

  it("is expired at and after the end, and for a bad instant", () => {
    expect(timeLeft("2026-11-26T10:00:00Z", now).expired).toBe(true);
    expect(timeLeft("2026-11-25T10:00:00Z", now).expired).toBe(true);
    expect(timeLeft("nope", now).expired).toBe(true);
  });
});
