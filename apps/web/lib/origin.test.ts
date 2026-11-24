import { describe, expect, it } from "vitest";
import { checkOrigin, expectedOrigin } from "./origin";

const url = "https://app.example.com/api/auth/logout";

function check(
  method: string,
  headers: Record<string, string>,
  appOrigin?: string | null,
  requestUrl = url,
) {
  return checkOrigin({ method, requestUrl, headers: new Headers(headers), appOrigin });
}

describe("checkOrigin", () => {
  it.each(["GET", "HEAD", "OPTIONS", "get"])("lets safe method %s through without headers", (method) => {
    expect(check(method, {})).toEqual({ ok: true });
  });

  it.each(["POST", "PUT", "PATCH", "DELETE", "post"])("accepts %s from the same origin", (method) => {
    expect(check(method, { origin: "https://app.example.com" })).toEqual({ ok: true });
  });

  it("rejects another origin", () => {
    expect(check("POST", { origin: "https://evil.example" })).toEqual({ ok: false, reason: "origin-mismatch" });
  });

  it.each([
    "http://app.example.com", // scheme differs
    "https://app.example.com:8443", // port differs
    "https://app.example.com.evil.example",
    "https://sub.app.example.com",
    "null", // sandboxed iframes, redirects across origins
    "",
  ])("rejects origin %j", (origin) => {
    expect(check("POST", { origin }).ok).toBe(false);
  });

  it("falls back to Sec-Fetch-Site when Origin is absent", () => {
    expect(check("DELETE", { "sec-fetch-site": "same-origin" })).toEqual({ ok: true });
    expect(check("DELETE", { "sec-fetch-site": "same-site" })).toEqual({ ok: false, reason: "cross-site" });
    expect(check("DELETE", { "sec-fetch-site": "cross-site" })).toEqual({ ok: false, reason: "cross-site" });
    expect(check("DELETE", { "sec-fetch-site": "none" })).toEqual({ ok: false, reason: "cross-site" });
  });

  it("rejects a mutating request with neither header", () => {
    expect(check("POST", {})).toEqual({ ok: false, reason: "no-origin" });
  });

  it("prefers Origin over Sec-Fetch-Site", () => {
    expect(check("POST", { origin: "https://evil.example", "sec-fetch-site": "same-origin" }).ok).toBe(false);
  });

  it("uses APP_ORIGIN when the request URL shows an internal host", () => {
    const internal = "http://0.0.0.0:3100/api/auth/logout";
    expect(check("POST", { origin: "https://app.example.com" }, "https://app.example.com", internal)).toEqual({
      ok: true,
    });
    expect(check("POST", { origin: "http://0.0.0.0:3100" }, "https://app.example.com", internal).ok).toBe(false);
    // without the override, the URL's own origin is the app's origin
    expect(check("POST", { origin: "http://0.0.0.0:3100" }, undefined, internal)).toEqual({ ok: true });
  });

  it("ignores a blank APP_ORIGIN and tolerates a trailing slash or path", () => {
    expect(check("POST", { origin: "https://app.example.com" }, "  ")).toEqual({ ok: true });
    expect(check("POST", { origin: "https://app.example.com" }, "https://app.example.com/")).toEqual({ ok: true });
  });

  it("fails closed when the URLs cannot be parsed", () => {
    expect(check("POST", { origin: "https://app.example.com" }, "not a url")).toEqual({
      ok: false,
      reason: "no-expected-origin",
    });
  });
});

describe("expectedOrigin", () => {
  it("is the origin of the request URL, or of the override", () => {
    expect(expectedOrigin("http://localhost:3100/api/bff/x?y=1")).toBe("http://localhost:3100");
    expect(expectedOrigin("http://localhost:3100/x", "https://app.example.com/some/path")).toBe("https://app.example.com");
    expect(expectedOrigin("nonsense")).toBeNull();
  });
});
