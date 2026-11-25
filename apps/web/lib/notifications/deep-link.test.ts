import { describe, expect, it } from "vitest";
import { resolveDeepLink } from "./deep-link";

describe("resolveDeepLink", () => {
  it("accepts relative app paths", () => {
    expect(resolveDeepLink({ path: "/" })).toBe("/");
    expect(resolveDeepLink({ path: "/s/hope/posts/12?tab=1" })).toBe("/s/hope/posts/12?tab=1");
  });

  it("opens the guardian screens for the paths the API sends", () => {
    expect(resolveDeepLink({ path: "/children" })).toBe("/family");
    expect(resolveDeepLink({ path: "/consents" })).toBe("/family/consent");
    expect(resolveDeepLink({ path: "/consents?x=1" })).toBe("/family/consent");
    expect(resolveDeepLink({ path: "/children/other" })).toBe("/children/other");
  });

  it("rejects anything that could leave the origin", () => {
    for (const path of ["//evil.example", "https://evil.example", "javascript:alert(1)", "/\\evil.example", "/a\tb", "relative", ""]) {
      expect(resolveDeepLink({ path })).toBeNull();
    }
  });

  it("is null without a usable path", () => {
    expect(resolveDeepLink(undefined)).toBeNull();
    expect(resolveDeepLink(null)).toBeNull();
    expect(resolveDeepLink({})).toBeNull();
    expect(resolveDeepLink({ path: 5 })).toBeNull();
  });
});
