import { describe, expect, it } from "vitest";
import { checkUploadTarget, MAX_PROXY_BYTES, parseContentLength } from "./upload-proxy";

const ID = "01a20f9f-b261-73ba-b18d-7a899638314c";
const ALLOWED = ["http://localhost:8343"];
const good = `http://localhost:8343/school-media-dev/users/u/avatar/${ID}/original?X-Amz-Signature=abc&X-Amz-Expires=600`;

describe("checkUploadTarget", () => {
  it("accepts a presigned URL of the media on an allowed origin", () => {
    expect(checkUploadTarget(good, ID, ALLOWED)?.pathname).toContain(ID);
    expect(checkUploadTarget(good, ID.toUpperCase(), ALLOWED)).not.toBeNull();
  });

  it("refuses other hosts, ports and schemes", () => {
    expect(checkUploadTarget(good.replace("localhost:8343", "evil.example"), ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget(good.replace("8343", "9999"), ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget(good.replace("http:", "file:"), ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget("http://localhost:8343@evil.example/x", ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget(good, ID, [])).toBeNull();
  });

  it("refuses URLs of another media or object, and unsigned ones", () => {
    expect(checkUploadTarget(good, "11111111-1111-4111-8111-111111111111", ALLOWED)).toBeNull();
    expect(checkUploadTarget(good.replace("/original", "/thumb"), ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget(good.replace("?X-Amz-Signature=abc&", "?"), ID, ALLOWED)).toBeNull();
  });

  it("refuses missing, malformed or non-uuid input", () => {
    expect(checkUploadTarget(null, ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget("not a url", ID, ALLOWED)).toBeNull();
    expect(checkUploadTarget(good, "../etc", ALLOWED)).toBeNull();
  });
});

describe("parseContentLength", () => {
  it("accepts positive sizes within the limit", () => {
    expect(parseContentLength("1234")).toBe(1234);
    expect(parseContentLength(String(MAX_PROXY_BYTES))).toBe(MAX_PROXY_BYTES);
  });

  it("refuses anything else", () => {
    expect(parseContentLength(null)).toBeNull();
    expect(parseContentLength("0")).toBeNull();
    expect(parseContentLength("-5")).toBeNull();
    expect(parseContentLength("12abc")).toBeNull();
    expect(parseContentLength(String(MAX_PROXY_BYTES + 1))).toBeNull();
  });
});
