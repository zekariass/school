import { describe, expect, it } from "vitest";
import { isValidSlug, slugify } from "./slug";

describe("isValidSlug", () => {
  it.each(["sunrise-academy", "abc", "school-2", "a1b2c3"])("accepts %s", (s) => expect(isValidSlug(s)).toBe(true));
  it.each(["ab", "", "Sunrise", "-abc", "abc-", "a--b", "a b", "a_b", "x".repeat(64), "ተማሪ"])("rejects %j", (s) =>
    expect(isValidSlug(s)).toBe(false),
  );
});

describe("slugify", () => {
  it("suggests a valid slug from a name", () => {
    expect(slugify("Sunrise Academy #2")).toBe("sunrise-academy-2");
    expect(slugify("  École Française  ")).toBe("ecole-francaise");
    expect(slugify("A---B")).toBe("a-b");
    expect(slugify("ተማሪ")).toBe("");
    expect(isValidSlug(slugify("Sunrise Academy"))).toBe(true);
  });

  it("never exceeds the length limit or ends with a hyphen", () => {
    const out = slugify(`${"word ".repeat(30)}`);
    expect(out.length).toBeLessThanOrEqual(63);
    expect(out.endsWith("-")).toBe(false);
  });
});
