import { describe, expect, it } from "vitest";
import { hasConsoleAccess, resolvePostSignInPath, schoolChoices, type GrantsLike } from "./post-sign-in";

function grant(type: string, school?: { slug: string; name: string }) {
  return { scope: { type }, schoolSlug: school?.slug, schoolName: school?.name };
}
const me = (...roleAssignments: GrantsLike["roleAssignments"][number][]): GrantsLike => ({ roleAssignments });

const sunrise = { slug: "sunrise", name: "Sunrise Academy" };
const moon = { slug: "moon", name: "Moon School" };

describe("resolvePostSignInPath", () => {
  it("goes to a safe next path first, even for administrators", () => {
    expect(resolvePostSignInPath({ me: me(grant("PLATFORM")), locale: "en", next: "/en/s/sunrise/classes" })).toBe(
      "/en/s/sunrise/classes",
    );
  });

  it.each([
    "//evil.com",
    "https://evil.com",
    "/\\evil.com",
    "javascript:alert(1)",
    "/api/auth/logout", // not a page of the app
    "/xx/platform", // unknown locale
    "en/platform",
  ])("ignores the unsafe next %j", (next) => {
    expect(resolvePostSignInPath({ me: me(grant("PLATFORM")), locale: "en", next })).toBe("/en/platform");
  });

  it("accepts next with a query or hash, in either locale", () => {
    expect(resolvePostSignInPath({ me: me(), locale: "en", next: "/am/platform?x=1" })).toBe("/am/platform?x=1");
    expect(resolvePostSignInPath({ me: me(), locale: "en", next: "/en" })).toBe("/en");
  });

  it.each(["PLATFORM", "COUNTRY", "ORGANIZATION"])("sends a %s administrator to the console", (type) => {
    expect(resolvePostSignInPath({ me: me(grant(type)), locale: "am" })).toBe("/am/platform");
  });

  it("prefers the console over schools for people with both", () => {
    expect(resolvePostSignInPath({ me: me(grant("SCHOOL", sunrise), grant("ORGANIZATION")), locale: "en" })).toBe(
      "/en/platform",
    );
  });

  it("opens the only school's workspace", () => {
    expect(resolvePostSignInPath({ me: me(grant("SCHOOL", sunrise)), locale: "en" })).toBe("/en/s/sunrise");
    // two roles at the same school are still one school
    expect(
      resolvePostSignInPath({ me: me(grant("SCHOOL", sunrise), grant("CAMPUS", sunrise)), locale: "en" }),
    ).toBe("/en/s/sunrise");
  });

  it("shows the picker for several schools", () => {
    expect(resolvePostSignInPath({ me: me(grant("SCHOOL", sunrise), grant("CAMPUS", moon)), locale: "en" })).toBe(
      "/en/schools",
    );
  });

  it("explains when there is nothing yet", () => {
    expect(resolvePostSignInPath({ me: me(), locale: "en" })).toBe("/en/no-access");
    // a school grant the API could not resolve to a slug doesn't count
    expect(resolvePostSignInPath({ me: me(grant("SCHOOL")), locale: "am" })).toBe("/am/no-access");
  });

  it("encodes the slug", () => {
    expect(resolvePostSignInPath({ me: me(grant("SCHOOL", { slug: "a b", name: "A" })), locale: "en" })).toBe(
      "/en/s/a%20b",
    );
  });
});

describe("schoolChoices and hasConsoleAccess", () => {
  it("lists distinct schools by name", () => {
    expect(schoolChoices(me(grant("SCHOOL", sunrise), grant("CAMPUS", sunrise), grant("SCHOOL", moon)))).toEqual([
      moon,
      sunrise,
    ]);
    expect(schoolChoices(me(grant("PLATFORM")))).toEqual([]);
  });

  it("falls back to the slug as name", () => {
    expect(schoolChoices(me({ scope: { type: "SCHOOL" }, schoolSlug: "x" }))).toEqual([{ slug: "x", name: "x" }]);
  });

  it("only platform, country and organization roles open the console", () => {
    expect(hasConsoleAccess(me(grant("SCHOOL", sunrise)))).toBe(false);
    expect(hasConsoleAccess(me(grant("COUNTRY")))).toBe(true);
  });
});
