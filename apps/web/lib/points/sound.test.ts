import { describe, expect, it } from "vitest";
import { parseMuted, playSound, SOUNDS, soundLength } from "./sound";

describe("sounds", () => {
  it("are short, with audible frequencies and sane gains", () => {
    for (const [name, tones] of Object.entries(SOUNDS)) {
      expect(tones.length, name).toBeGreaterThan(0);
      for (const t of tones) {
        expect(t.frequency).toBeGreaterThan(100);
        expect(t.frequency).toBeLessThan(4000);
        expect(t.duration).toBeGreaterThan(0);
        expect(t.gain ?? 0.4).toBeLessThanOrEqual(0.6);
      }
    }
    expect(soundLength("award")).toBeLessThan(1);
    expect(soundLength("alarm")).toBeLessThan(2);
  });
});

describe("mute", () => {
  it("is on only for the stored 1", () => {
    expect(parseMuted("1")).toBe(true);
    expect(parseMuted("0")).toBe(false);
    expect(parseMuted(null)).toBe(false);
    expect(parseMuted(undefined)).toBe(false);
  });

  it("plays nothing (and does not throw) when muted or without audio", () => {
    expect(() => playSound("award", true)).not.toThrow();
    expect(() => playSound("award", false)).not.toThrow();
  });
});
