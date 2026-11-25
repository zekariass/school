import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createRefresher } from "./refresh";

describe("createRefresher", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("asks twice after the last action", () => {
    const run = vi.fn();
    createRefresher().schedule("class-1", run);
    vi.advanceTimersByTime(2999);
    expect(run).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(run).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(6000);
    expect(run).toHaveBeenCalledTimes(2);
  });

  it("a newer action restarts the timers, so an early stale answer cannot overwrite it", () => {
    const run = vi.fn();
    const refresher = createRefresher();
    refresher.schedule("class-1", run);
    vi.advanceTimersByTime(2000);
    refresher.schedule("class-1", run); // the undo
    vi.advanceTimersByTime(2000); // 4s after the award, but only 2s after the undo
    expect(run).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(run).toHaveBeenCalledTimes(1);
  });

  it("keeps classes apart and can cancel", () => {
    const a = vi.fn();
    const b = vi.fn();
    const refresher = createRefresher();
    refresher.schedule("a", a);
    refresher.schedule("b", b);
    refresher.cancel("a");
    vi.advanceTimersByTime(10_000);
    expect(a).not.toHaveBeenCalled();
    expect(b).toHaveBeenCalledTimes(2);
  });
});
