import { describe, expect, it } from "vitest";
import { formatCountdown, readProblem, retryAfterSeconds, secondsUntil } from "./problem";

describe("readProblem", () => {
  it("returns a problem body, else null", async () => {
    const body = { type: "x", title: "Too many", status: 429, code: "auth.rate_limited" };
    expect(await readProblem(new Response(JSON.stringify(body)))).toEqual(body);
    expect(await readProblem(new Response("<html>"))).toBeNull();
    expect(await readProblem(new Response("123"))).toBeNull();
    expect(await readProblem(new Response(""))).toBeNull();
  });
});

describe("retryAfterSeconds", () => {
  it.each([
    ["30", 30],
    [" 5 ", 5],
    ["0", 0],
    ["1.2", 2],
  ])("reads %j as %i", (value, expected) => {
    expect(retryAfterSeconds(value)).toBe(expected);
  });

  it.each([null, undefined, "", "soon", "-3", "Wed, 21 Oct 2026 07:28:00 GMT"])("ignores %j", (value) => {
    expect(retryAfterSeconds(value)).toBeNull();
  });
});

describe("countdown helpers", () => {
  it("formats minutes and seconds", () => {
    expect(formatCountdown(245)).toBe("4:05");
    expect(formatCountdown(59)).toBe("0:59");
    expect(formatCountdown(0)).toBe("0:00");
    expect(formatCountdown(-4)).toBe("0:00");
  });

  it("counts whole seconds up to a deadline and stops at zero", () => {
    expect(secondsUntil(10_000, 0)).toBe(10);
    expect(secondsUntil(10_001, 0)).toBe(11);
    expect(secondsUntil(10_000, 10_000)).toBe(0);
    expect(secondsUntil(10_000, 20_000)).toBe(0);
  });
});
