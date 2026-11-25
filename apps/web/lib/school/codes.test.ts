import { describe, expect, it } from "vitest";
import {
  earliestExpiry,
  formatTextCode,
  paginate,
  qrPayload,
  sheetForClass,
  splitPrintable,
  type CodeEntry,
  type CodeSheet,
} from "./codes";

const entry = (over: Partial<CodeEntry> = {}): CodeEntry => ({
  studentId: "s1",
  displayName: "Abebe",
  expiresAt: "2027-01-01T00:00:00Z",
  qrToken: "AbCdEfGhIjKlMnOpQrStUv",
  textCode: "ABCDE-FGHJK",
  ...over,
});

describe("qrPayload", () => {
  it("is the bare token: no URL, no prefix", () => {
    expect(qrPayload(entry())).toBe("AbCdEfGhIjKlMnOpQrStUv");
  });

  it("is null when no QR code was issued (text-only sheets)", () => {
    expect(qrPayload(entry({ qrToken: null }))).toBeNull();
    expect(qrPayload(entry({ qrToken: undefined }))).toBeNull();
    expect(qrPayload(entry({ qrToken: "  " }))).toBeNull();
  });
});

describe("formatTextCode", () => {
  it("shows XXXXX-XXXXX in upper case", () => {
    expect(formatTextCode("abcdefghjk")).toBe("ABCDE-FGHJK");
    expect(formatTextCode("ABCDE-FGHJK")).toBe("ABCDE-FGHJK");
    expect(formatTextCode(" abcde fghjk ")).toBe("ABCDE-FGHJK");
  });
  it("leaves short input alone", () => {
    expect(formatTextCode("abc")).toBe("ABC");
  });
});

describe("sheetForClass", () => {
  const sheet: CodeSheet = { classId: "c1", className: "8A", kind: "BOTH", issuedAt: 1, codes: [entry()] };

  it("returns the sheet only for the class it was issued for", () => {
    expect(sheetForClass(sheet, "c1")).toBe(sheet);
    expect(sheetForClass(sheet, "c2")).toBeNull();
  });

  it("is empty after a reload (state lost) or without codes", () => {
    expect(sheetForClass(null, "c1")).toBeNull();
    expect(sheetForClass(undefined, "c1")).toBeNull();
    expect(sheetForClass({ ...sheet, codes: [] }, "c1")).toBeNull();
  });
});

describe("paginate", () => {
  it("fills pages of eight", () => {
    const pages = paginate(Array.from({ length: 19 }, (_, i) => i));
    expect(pages.map((p) => p.length)).toEqual([8, 8, 3]);
    expect(paginate([])).toEqual([]);
  });
});

describe("splitPrintable", () => {
  const withQr = entry({ studentId: "a", textCode: null });
  const withText = entry({ studentId: "b", qrToken: null });
  const none = entry({ studentId: "c", qrToken: null, textCode: null });

  it("keeps the cards that have what the chosen kind needs", () => {
    expect(splitPrintable([withQr, withText, none], "QR").printable.map((e) => e.studentId)).toEqual(["a"]);
    expect(splitPrintable([withQr, withText, none], "TEXT").printable.map((e) => e.studentId)).toEqual(["b"]);
    const both = splitPrintable([withQr, withText, none], "BOTH");
    expect(both.printable.map((e) => e.studentId)).toEqual(["a", "b"]);
    expect(both.missing.map((e) => e.studentId)).toEqual(["c"]);
  });
});

describe("earliestExpiry", () => {
  it("finds the first code to expire", () => {
    expect(
      earliestExpiry([entry({ expiresAt: "2027-03-01T00:00:00Z" }), entry({ expiresAt: "2027-01-01T00:00:00Z" })]),
    ).toBe("2027-01-01T00:00:00Z");
    expect(earliestExpiry([])).toBeNull();
  });
});
