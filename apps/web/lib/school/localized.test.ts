import { describe, expect, it } from "vitest";
import { compactTexts, hasText, inputLanguages } from "./localized";

describe("inputLanguages", () => {
  it("offers the country's languages first and keeps a saved language the country does not list", () => {
    expect(inputLanguages(["xa", "xb"], undefined)).toEqual(["xa", "xb"]);
    expect(inputLanguages(["xa", "xb"], { xb: "x", xz: "y" })).toEqual(["xa", "xb", "xz"]);
  });
});

describe("compactTexts", () => {
  it("trims and leaves out empty languages", () => {
    expect(compactTexts({ xa: "  Kindness ", xb: "   ", xz: "" })).toEqual({ xa: "Kindness" });
  });
});

describe("hasText", () => {
  it("is true when any language has text", () => {
    expect(hasText({ xa: "", xb: " a " })).toBe(true);
    expect(hasText({ xa: " ", xb: "" })).toBe(false);
    expect(hasText({})).toBe(false);
  });
});
