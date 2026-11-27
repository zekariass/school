import { describe, expect, it } from "vitest";
import { DEFAULT_WAIT_SECONDS, cameraProblemOf, failureOf, frameDue, scanSize } from "./student-code";

describe("failureOf", () => {
  it("shows every wrong, expired or revoked code as the API's one generic sentence", () => {
    expect(failureOf({ status: 400, code: "auth.invalid_code", detail: "That code did not work." })).toEqual({ kind: "invalid", detail: "That code did not work." });
    expect(failureOf({ status: 400, code: "auth.invalid_code" })).toEqual({ kind: "invalid", detail: undefined });
  });

  it("treats a refused input or an account that cannot sign in like a wrong code (nothing more is revealed)", () => {
    expect(failureOf({ status: 400, code: "bff_bad_request" }).kind).toBe("invalid");
    expect(failureOf({ status: 401, code: "auth.account_inactive" }).kind).toBe("invalid");
    expect(failureOf({ status: 403 }).kind).toBe("invalid");
  });

  it("reads Retry-After on a 429 and falls back to a minute", () => {
    expect(failureOf({ status: 429, retryAfter: "42" })).toEqual({ kind: "rateLimited", seconds: 42 });
    expect(failureOf({ status: 429 })).toEqual({ kind: "rateLimited", seconds: DEFAULT_WAIT_SECONDS });
    expect(failureOf({ status: 400, code: "auth.rate_limited", retryAfter: "5" })).toEqual({ kind: "rateLimited", seconds: 5 });
  });

  it("says the service is unavailable for a server error or a lost connection", () => {
    expect(failureOf({ status: 502, code: "bff_upstream_unavailable" })).toEqual({ kind: "unavailable" });
    expect(failureOf({ status: 503 })).toEqual({ kind: "unavailable" });
    expect(failureOf({ status: 0 })).toEqual({ kind: "unavailable" });
  });
});

describe("cameraProblemOf", () => {
  it("tells a refused permission from a missing or busy camera", () => {
    expect(cameraProblemOf({ name: "NotAllowedError" })).toBe("denied");
    expect(cameraProblemOf({ name: "SecurityError" })).toBe("denied");
    expect(cameraProblemOf({ name: "NotFoundError" })).toBe("unavailable");
    expect(cameraProblemOf({ name: "NotReadableError" })).toBe("unavailable");
    expect(cameraProblemOf(new Error("x"))).toBe("unavailable");
    expect(cameraProblemOf(null)).toBe("unavailable");
  });
});

describe("scan pacing", () => {
  it("decodes a frame only when the interval has passed", () => {
    expect(frameDue(1000, 900)).toBe(false);
    expect(frameDue(1000, 875)).toBe(true);
  });

  it("shrinks a large frame to the decoder's size and never enlarges a small one", () => {
    expect(scanSize(1920, 1080)).toEqual({ width: 640, height: 360 });
    expect(scanSize(1080, 1920)).toEqual({ width: 360, height: 640 });
    expect(scanSize(320, 240)).toEqual({ width: 320, height: 240 });
    expect(scanSize(0, 0)).toEqual({ width: 0, height: 0 });
  });
});
