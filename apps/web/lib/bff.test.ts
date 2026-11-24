import { describe, expect, it } from "vitest";
import {
  bffProblem,
  buildUpstreamHeaders,
  buildUpstreamUrl,
  filterResponseHeaders,
  isAllowedUpstreamPath,
} from "./bff";

describe("isAllowedUpstreamPath", () => {
  it.each([
    [["api", "v1", "meta"]],
    [["api", "v1", "schools", "0190-abc_1.2~x", "classes"]],
  ])("allows %j", (segments) => {
    expect(isAllowedUpstreamPath(segments)).toBe(true);
  });

  it.each([
    [[]],
    [["api", "v1"]],
    [["actuator", "health"]],
    [["api", "v2", "meta"]],
    [["api", "v1", "..", "secret"]],
    [["api", "v1", ".", "meta"]],
    [["api", "v1", "..%2fsecret"]],
    [["api", "v1", "a/b"]],
    [["api", "v1", "a\\b"]],
    [["api", "v1", ""]],
    [["api", "v1", "meta?x=1"]],
    [["api", "v1", "meta#frag"]],
    [["api", "v1", "me ta"]],
    [["api", "v1", "%2e%2e"]],
    [["api", "v1", "meta\n"]],
    [["API", "v1", "meta"]],
  ])("rejects %j", (segments) => {
    expect(isAllowedUpstreamPath(segments)).toBe(false);
  });
});

describe("buildUpstreamUrl", () => {
  it("joins origin, path and query string", () => {
    const url = buildUpstreamUrl("http://localhost:8180/", ["api", "v1", "meta"], "?a=1&b=x%20y");
    expect(url?.toString()).toBe("http://localhost:8180/api/v1/meta?a=1&b=x%20y");
  });

  it("works without a query string", () => {
    expect(buildUpstreamUrl("http://api:8080", ["api", "v1", "meta"], "")?.toString()).toBe(
      "http://api:8080/api/v1/meta",
    );
  });

  it("returns null for traversal attempts", () => {
    expect(buildUpstreamUrl("http://api", ["api", "v1", "..", "..", "actuator"], "")).toBeNull();
    expect(buildUpstreamUrl("http://api", ["actuator", "health"], "")).toBeNull();
  });
});

describe("buildUpstreamHeaders", () => {
  const incoming = new Headers({
    accept: "application/json",
    "accept-language": "am",
    "content-type": "application/json",
    "idempotency-key": "k-1",
    "x-request-id": "r-1",
    cookie: "sch_at=secret; sch_rt=secret",
    authorization: "Bearer browser-supplied",
    host: "evil.example",
    "x-forwarded-for": "1.2.3.4",
    origin: "https://evil.example",
  });

  it("forwards only allowlisted headers and drops cookie and authorization", () => {
    const out = buildUpstreamHeaders(incoming);
    expect([...out.keys()].sort()).toEqual([
      "accept",
      "accept-language",
      "content-type",
      "idempotency-key",
      "x-request-id",
    ]);
    expect(out.has("cookie")).toBe(false);
    expect(out.has("authorization")).toBe(false);
  });

  it("adds the server-side bearer token, overriding any browser-supplied one", () => {
    expect(buildUpstreamHeaders(incoming, "tok").get("authorization")).toBe("Bearer tok");
  });

  it("omits authorization without a token", () => {
    expect(buildUpstreamHeaders(new Headers()).has("authorization")).toBe(false);
  });
});

describe("filterResponseHeaders", () => {
  it("keeps allowlisted headers and drops cookies and hop-by-hop headers", () => {
    const out = filterResponseHeaders(
      new Headers({
        "content-type": "application/json",
        "x-request-id": "r-1",
        "cache-control": "no-store",
        "retry-after": "3",
        "idempotent-replayed": "true",
        "set-cookie": "a=b",
        "content-encoding": "gzip",
        "content-length": "10",
        "transfer-encoding": "chunked",
      }),
    );
    expect([...out.keys()].sort()).toEqual([
      "cache-control",
      "content-type",
      "idempotent-replayed",
      "retry-after",
      "x-request-id",
    ]);
  });
});

describe("bffProblem", () => {
  it("builds an RFC 9457-shaped body with a stable code", () => {
    expect(bffProblem("bff_upstream_unavailable", 502, "Unavailable")).toEqual({
      type: "urn:school:problem:bff_upstream_unavailable",
      title: "Unavailable",
      status: 502,
      code: "bff_upstream_unavailable",
    });
  });
});
