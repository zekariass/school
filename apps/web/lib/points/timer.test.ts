import { addTime, countdown, pause, start, stopwatch } from "@school/classroom-toolkit";
import { describe, expect, it } from "vitest";
import { clockView, parseMinutes } from "./timer";

describe("parseMinutes", () => {
  it("reads whole and decimal minutes", () => {
    expect(parseMinutes("5")).toBe(300_000);
    expect(parseMinutes(" 1,5 ")).toBe(90_000);
    expect(parseMinutes("0.5")).toBe(30_000);
  });

  it("rejects empty, zero, negative, huge and non-numeric input", () => {
    for (const bad of ["", "0", "-2", "181", "abc", "NaN"]) expect(parseMinutes(bad), bad).toBeNull();
  });
});

describe("clockView with an injected clock", () => {
  it("counts a countdown down and finishes at zero", () => {
    let state = start(countdown(65_000), 1_000);
    expect(clockView(state, 1_000)).toEqual({ text: "1:05", running: true, finished: false });
    expect(clockView(state, 1_000 + 5_100).text).toBe("1:00");
    expect(clockView(state, 1_000 + 6_000).text).toBe("0:59");
    const end = 1_000 + 65_000;
    expect(clockView(state, end)).toEqual({ text: "0:00", running: false, finished: true });
    state = pause(state, 1_000 + 10_000);
    expect(clockView(state, 999_999)).toEqual({ text: "0:55", running: false, finished: false });
  });

  it("counts a stopwatch up", () => {
    const state = start(stopwatch(), 0);
    expect(clockView(state, 61_500)).toEqual({ text: "1:01", running: true, finished: false });
  });

  it("adds a minute to a running countdown", () => {
    const state = addTime(start(countdown(60_000), 0), 60_000, 30_000);
    expect(clockView(state, 30_000).text).toBe("1:30");
  });
});
