import { describe, expect, it } from "vitest";
import { BACKOFF_MAX_MS, reconnectDelay } from "./backoff";

describe("reconnectDelay", () => {
  it("grows exponentially: half fixed, half jitter", () => {
    expect(reconnectDelay(0, () => 0)).toBe(500);
    expect(reconnectDelay(0, () => 1)).toBe(1000);
    expect(reconnectDelay(1, () => 0)).toBe(1000);
    expect(reconnectDelay(2, () => 1)).toBe(4000);
    expect(reconnectDelay(3, () => 0.5)).toBe(6000);
  });

  it("is capped at a minute", () => {
    expect(reconnectDelay(6, () => 1)).toBe(BACKOFF_MAX_MS);
    expect(reconnectDelay(50, () => 1)).toBe(BACKOFF_MAX_MS);
    expect(reconnectDelay(50, () => 0)).toBe(BACKOFF_MAX_MS / 2);
  });

  it("never goes below the fixed half or above the ceiling", () => {
    for (let attempt = 0; attempt < 12; attempt++) {
      const ceiling = Math.min(BACKOFF_MAX_MS, 1000 * 2 ** attempt);
      for (const r of [0, 0.25, 0.99]) {
        const d = reconnectDelay(attempt, () => r);
        expect(d).toBeGreaterThanOrEqual(ceiling / 2);
        expect(d).toBeLessThanOrEqual(ceiling);
      }
    }
  });

  it("treats a negative attempt as the first", () => {
    expect(reconnectDelay(-3, () => 0)).toBe(500);
  });
});
