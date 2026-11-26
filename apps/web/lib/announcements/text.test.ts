import { describe, expect, it } from "vitest";
import { linkify, oneLine, safeHref } from "./text";

const joined = (input: string) => linkify(input).map((s) => s.text).join("");

describe("linkify", () => {
  it("keeps plain text as one run", () => {
    expect(linkify("School is closed on Friday.")).toEqual([{ kind: "text", text: "School is closed on Friday." }]);
    expect(linkify("")).toEqual([]);
  });

  it("makes http and https addresses links and nothing else", () => {
    expect(linkify("See https://school.example/news for details")).toEqual([
      { kind: "text", text: "See " },
      { kind: "link", text: "https://school.example/news", href: "https://school.example/news" },
      { kind: "text", text: " for details" },
    ]);
    expect(linkify("http://school.example")[0]).toMatchObject({ kind: "link", href: "http://school.example/" });
  });

  it("never links other schemes", () => {
    for (const text of ["javascript:alert(1)", "data:text/html,<b>x</b>", "file:///etc/passwd", "ftp://x.example/f", "mailto:a@b.example", "vbscript:x"]) {
      expect(linkify(text).every((s) => s.kind === "text")).toBe(true);
    }
    // a scheme hidden inside a http-looking run does not make it a link either
    expect(safeHref("javascript://https://x.example")).toBeNull();
    expect(safeHref("HTTPS://X.EXAMPLE/a")).toBe("https://x.example/a");
  });

  it("keeps sentence punctuation out of the link", () => {
    const segments = linkify("Read https://school.example/a, then (https://school.example/b). Done!");
    expect(segments.filter((s) => s.kind === "link").map((s) => s.text)).toEqual(["https://school.example/a", "https://school.example/b"]);
    expect(joined("Read https://school.example/a, then (https://school.example/b). Done!")).toBe("Read https://school.example/a, then (https://school.example/b). Done!");
  });

  it("keeps a bracket that closes one opened inside the address", () => {
    expect(linkify("https://en.example/wiki/Foo_(bar)")[0]).toMatchObject({ kind: "link", text: "https://en.example/wiki/Foo_(bar)" });
    expect(linkify("(https://en.example/wiki/Foo)")[1]).toMatchObject({ kind: "link", text: "https://en.example/wiki/Foo" });
  });

  it("refuses addresses with credentials (a disguised host) and over-long ones", () => {
    expect(linkify("https://school.example@evil.example/login").every((s) => s.kind === "text")).toBe(true);
    expect(linkify("https://user:pw@evil.example").every((s) => s.kind === "text")).toBe(true);
    expect(safeHref(`https://x.example/${"a".repeat(2100)}`)).toBeNull();
  });

  it("does not treat markup as anything: the characters stay text for React to escape", () => {
    const hostile = `<img src=x onerror=alert(1)> <script>alert(1)</script> https://school.example/"onmouseover="x`;
    const segments = linkify(hostile);
    expect(joined(hostile)).toBe(hostile);
    for (const s of segments) {
      if (s.kind === "link") {
        expect(s.href.startsWith("https://")).toBe(true);
        expect(s.href).not.toContain('"');
        expect(s.href).not.toContain("<");
      }
    }
    expect(segments[0]).toEqual({ kind: "text", text: expect.stringContaining("<img src=x onerror=alert(1)>") });
  });

  it("round-trips any input", () => {
    for (const input of ["a https://x.example b https://y.example/c?d=e#f g", "https://", "http:// x", "ttps://x.example", "https://x.example)))", "x\nhttps://x.example\ny"]) {
      expect(joined(input)).toBe(input);
    }
  });

  it("handles line breaks and non-latin text around links", () => {
    const segments = linkify("ሰላም https://school.example/am\nሁለተኛ መስመር");
    expect(segments.map((s) => s.kind)).toEqual(["text", "link", "text"]);
  });
});

describe("oneLine", () => {
  it("collapses white space and cuts long text with an ellipsis", () => {
    expect(oneLine("a\n\n  b   c", 20)).toBe("a b c");
    expect(oneLine("abcdefghij", 5)).toBe("abcd…");
    expect(oneLine("ab", 5)).toBe("ab");
  });

  it("does not split a character made of two UTF-16 units", () => {
    expect(oneLine("😀😀😀😀", 3)).toBe("😀😀…");
  });
});
