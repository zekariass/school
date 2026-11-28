import { describe, expect, it } from "vitest";
import { ROLE_CHIPS, firstWords, postVariant, roleChipTone, splitFirstLine } from "./post-style";

describe("post variants", () => {
  it("follow the API's postType and nothing else", () => {
    expect(postVariant({ postType: "ACHIEVEMENT" })).toBe("achievement");
    expect(postVariant({ postType: "ACTIVITY" })).toBe("activity");
    expect(postVariant({ postType: "POST" })).toBe("standard");
  });
});

describe("role chips", () => {
  it("cover every author role, staff in the accent tone", () => {
    expect(ROLE_CHIPS).toHaveLength(4);
    expect(roleChipTone("TEACHER")).toBe("accent");
    expect(roleChipTone("SCHOOL_ADMIN")).toBe("accent");
    expect(roleChipTone("GUARDIAN")).toBe("quiet");
    expect(roleChipTone("STUDENT")).toBe("quiet");
  });
});

describe("splitFirstLine", () => {
  it("keeps the text exactly and bolds only a short first line followed by more", () => {
    const body = "Scientists in action!\nToday we planted seeds.";
    const { first, rest } = splitFirstLine(body);
    expect(first).toBe("Scientists in action!");
    expect(first + rest).toBe(body);
  });
  it("does not split a single paragraph or a long first line", () => {
    expect(splitFirstLine("Just one paragraph")).toEqual({ first: "", rest: "Just one paragraph" });
    const long = `${"x".repeat(150)}\nmore`;
    expect(splitFirstLine(long)).toEqual({ first: "", rest: long });
    expect(splitFirstLine("\nstarts with a break")).toEqual({ first: "", rest: "\nstarts with a break" });
  });
});

describe("firstWords", () => {
  it("shortens to a few words with an ellipsis", () => {
    expect(firstWords("Star readers of the week are Hana and Dawit", 4)).toBe("Star readers of the…");
    expect(firstWords("Well done", 4)).toBe("Well done");
    expect(firstWords(undefined)).toBe("");
    expect(firstWords("a".repeat(80), 5, 10)).toBe(`${"a".repeat(10)}…`);
  });
});
