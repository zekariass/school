import { describe, expect, it } from "vitest";
import { avatarLook, hashString, initialsOf } from "./avatar";

describe("initialsOf", () => {
  it("takes the first letters of the first and last word", () => {
    expect(initialsOf("Abebe Kebede Tadesse")).toBe("AT");
    expect(initialsOf("  tigist  ")).toBe("T");
    expect(initialsOf("አበበ ከበደ")).toBe("አከ");
  });

  it("copes with empty names", () => {
    expect(initialsOf("   ")).toBe("?");
  });
});

describe("avatarLook", () => {
  it("is stable per student and differs between students", () => {
    expect(avatarLook("s1", "A B")).toEqual(avatarLook("s1", "A B"));
    expect(hashString("s1")).not.toBe(hashString("s2"));
  });

  it("honours an emoji and a color from the avatar configuration", () => {
    const look = avatarLook("s1", "A B", { emoji: "🦁", color: "#ff8800" });
    expect(look).toMatchObject({ emoji: "🦁", background: "#ff8800" });
  });

  it("ignores malformed configuration", () => {
    const look = avatarLook("s1", "A B", { emoji: 7, color: "orange" });
    expect(look.emoji).toBeNull();
    expect(look.background).toMatch(/^hsl\(/);
    expect(avatarLook("s1", "A B", ["x"]).emoji).toBeNull();
  });
});
