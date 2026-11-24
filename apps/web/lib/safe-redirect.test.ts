import { describe, expect, it } from "vitest";
import { safeNextPathOr, sanitizeNextPath } from "./safe-redirect";

describe("sanitizeNextPath", () => {
  it.each(["/", "/en/s/demo", "/en/s/demo?tab=1&x=%2F", "/am/platform#top"])(
    "accepts %s",
    (value) => {
      expect(sanitizeNextPath(value)).toBe(value);
    },
  );

  it.each([
    "//evil.com",
    "///evil.com",
    "/\\evil.com",
    "/en\\..\\evil",
    "https://evil.com",
    "http://evil.com/en",
    "javascript:alert(1)",
    "en/s/demo",
    "",
    "/\t/evil.com",
    "/\n/evil.com",
    "/en/s/\u0000",
    "/" + "a".repeat(3000),
  ])("rejects %j", (value) => {
    expect(sanitizeNextPath(value)).toBeNull();
  });

  it("rejects null and undefined", () => {
    expect(sanitizeNextPath(null)).toBeNull();
    expect(sanitizeNextPath(undefined)).toBeNull();
  });
});

describe("safeNextPathOr", () => {
  it("falls back for unsafe values", () => {
    expect(safeNextPathOr("//evil.com", "/en")).toBe("/en");
    expect(safeNextPathOr("/en/s/x", "/en")).toBe("/en/s/x");
  });
});
