import { describe, expect, it } from "vitest";
import { qrMatrix, qrPath } from "./qr";

describe("qrMatrix", () => {
  it("is deterministic and square", () => {
    const a = qrMatrix("AbCdEfGhIjKlMnOpQrStUv");
    const b = qrMatrix("AbCdEfGhIjKlMnOpQrStUv");
    expect(a).toEqual(b);
    expect(a.dark).toHaveLength(a.size * a.size);
    expect(a.size).toBeGreaterThanOrEqual(21);
  });

  it("changes with the text", () => {
    expect(qrMatrix("AAAA").dark).not.toEqual(qrMatrix("BBBB").dark);
  });

  it("starts with the finder pattern", () => {
    const m = qrMatrix("hello");
    // the top-left 7x7 finder: solid first row
    expect(m.dark.slice(0, 7).every(Boolean)).toBe(true);
  });
});

describe("qrPath", () => {
  it("draws runs of dark modules", () => {
    expect(qrPath({ size: 3, dark: [true, true, false, false, false, false, false, true, true] })).toBe(
      "M0 0h2v1h-2zM1 2h2v1h-2z",
    );
    expect(qrPath({ size: 2, dark: [false, false, false, false] })).toBe("");
  });
});
