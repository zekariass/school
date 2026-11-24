import { describe, expect, it } from "vitest";
import { editorKindFor, parseEditorText, summarizeValue, toEditorText } from "./definitions";

describe("editorKindFor", () => {
  it("maps JSON Schema types to editors", () => {
    expect(editorKindFor({ type: "boolean" })).toEqual({ kind: "boolean" });
    expect(editorKindFor({ type: "integer", minimum: 1, maximum: 10 })).toEqual({
      kind: "number",
      integer: true,
      min: 1,
      max: 10,
    });
    expect(editorKindFor({ type: "number" })).toEqual({ kind: "number", integer: false, min: undefined, max: undefined });
    expect(editorKindFor({ type: "string", maxLength: 20 })).toEqual({ kind: "string", maxLength: 20 });
    expect(editorKindFor({ type: "string", enum: ["a", "b"] })).toEqual({ kind: "enum", options: ["a", "b"] });
  });

  it.each([null, undefined, "x", [], {}, { type: "array", items: { type: "string" } }, { type: ["string", "null"] }, { enum: [1, 2] }])(
    "falls back to JSON for %j",
    (schema) => {
      expect(editorKindFor(schema)).toEqual({ kind: "json" });
    },
  );
});

describe("parseEditorText", () => {
  it("parses booleans and enums strictly", () => {
    expect(parseEditorText({ kind: "boolean" }, "true")).toEqual({ ok: true, value: true });
    expect(parseEditorText({ kind: "boolean" }, "false")).toEqual({ ok: true, value: false });
    expect(parseEditorText({ kind: "boolean" }, "yes")).toEqual({ ok: false });
    expect(parseEditorText({ kind: "enum", options: ["a", "b"] }, "b")).toEqual({ ok: true, value: "b" });
    expect(parseEditorText({ kind: "enum", options: ["a", "b"] }, "c")).toEqual({ ok: false });
  });

  it("parses numbers within their bounds", () => {
    const kind = { kind: "number", integer: true, min: 1, max: 10 } as const;
    expect(parseEditorText(kind, "5")).toEqual({ ok: true, value: 5 });
    expect(parseEditorText(kind, "0")).toEqual({ ok: false });
    expect(parseEditorText(kind, "11")).toEqual({ ok: false });
    expect(parseEditorText(kind, "2.5")).toEqual({ ok: false });
    expect(parseEditorText(kind, "")).toEqual({ ok: false });
    expect(parseEditorText(kind, "abc")).toEqual({ ok: false });
    expect(parseEditorText({ kind: "number", integer: false }, "2.5")).toEqual({ ok: true, value: 2.5 });
  });

  it("keeps strings as typed and parses JSON", () => {
    expect(parseEditorText({ kind: "string" }, " hi ")).toEqual({ ok: true, value: " hi " });
    expect(parseEditorText({ kind: "json" }, '{"a": [1, 2]}')).toEqual({ ok: true, value: { a: [1, 2] } });
    expect(parseEditorText({ kind: "json" }, "null")).toEqual({ ok: true, value: null });
    expect(parseEditorText({ kind: "json" }, "{oops")).toEqual({ ok: false });
  });
});

describe("toEditorText and summarizeValue", () => {
  it("round-trips through the editors", () => {
    expect(toEditorText({ kind: "boolean" }, true)).toBe("true");
    expect(toEditorText({ kind: "number", integer: false }, 3)).toBe("3");
    expect(toEditorText({ kind: "string" }, null)).toBe("");
    expect(toEditorText({ kind: "json" }, { a: 1 })).toBe('{\n  "a": 1\n}');
    expect(toEditorText({ kind: "json" }, undefined)).toBe("null");
  });

  it("shortens long values", () => {
    expect(summarizeValue(true)).toBe("true");
    expect(summarizeValue("abc")).toBe("abc");
    expect(summarizeValue("x".repeat(100), 10)).toHaveLength(10);
    expect(summarizeValue(undefined)).toBe("null");
  });
});
