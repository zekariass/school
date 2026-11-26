import { describe, expect, it } from "vitest";
import { charCount, displayText, messageSegments, previewLine } from "./text";

describe("message text is plain text", () => {
  it("keeps markup as characters: the segments give back the input and contain no link", () => {
    const html = '<img src=x onerror="alert(1)"><script>alert(2)</script><b>bold</b>';
    const segments = messageSegments(html);
    expect(segments.map((s) => s.text).join("")).toBe(html);
    expect(segments.every((s) => s.kind === "text")).toBe(true);
  });

  it("links only http and https addresses without credentials", () => {
    const text = "see https://school.example/page, javascript:alert(1) and https://good.example@evil.example and data:text/html,x";
    const links = messageSegments(text).filter((s) => s.kind === "link");
    expect(links).toHaveLength(1);
    expect(links[0]).toMatchObject({ href: "https://school.example/page" });
  });

  it("does not turn an html anchor into a link element", () => {
    const segments = messageSegments('<a href="javascript:alert(1)">click</a>');
    expect(segments.some((s) => s.kind === "link")).toBe(false);
  });

  it("strips control, bidirectional and zero-width characters", () => {
    expect(displayText("a\u202Eb\u200Bc\u0000d\uFEFFe")).toBe("abcde");
    expect(displayText("line1\r\nline2\n\n\n\nline3")).toBe("line1\nline2\n\nline3");
  });

  it("leaves emoji and Ethiopic text alone", () => {
    expect(displayText("ሰላም 👍🏽 ❤️")).toBe("ሰላም 👍🏽 ❤️");
  });
});

describe("previews and counting", () => {
  it("has no preview for a tombstone (the API sends none)", () => {
    expect(previewLine(undefined)).toBeNull();
  });

  it("collapses whitespace and shortens", () => {
    expect(previewLine("a\n\n b   c", 90)).toBe("a b c");
    expect(previewLine("x".repeat(200), 10)?.length).toBe(10);
  });

  it("counts code points, not UTF-16 units", () => {
    expect(charCount("👍👍")).toBe(2);
    expect("👍👍".length).toBe(4);
  });
});
