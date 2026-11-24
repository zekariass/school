/**
 * Picks an input for a setting from its definition's `valueSchema` (a JSON Schema
 * subset: type, enum, minimum/maximum, minLength/maxLength) and converts between
 * what the user typed and the JSON value the API stores. The API validates again.
 */

export type EditorKind =
  | { kind: "boolean" }
  | { kind: "number"; integer: boolean; min?: number; max?: number }
  | { kind: "enum"; options: string[] }
  | { kind: "string"; maxLength?: number }
  /** Arrays, objects, unions: edited as JSON text. */
  | { kind: "json" };

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function editorKindFor(schema: unknown): EditorKind {
  if (!isRecord(schema)) return { kind: "json" };
  const { type, enum: options } = schema;
  if (Array.isArray(options) && options.length > 0 && options.every((o) => typeof o === "string")) {
    return { kind: "enum", options: options as string[] };
  }
  if (type === "boolean") return { kind: "boolean" };
  if (type === "number" || type === "integer") {
    const min = typeof schema.minimum === "number" ? schema.minimum : undefined;
    const max = typeof schema.maximum === "number" ? schema.maximum : undefined;
    return { kind: "number", integer: type === "integer", min, max };
  }
  if (type === "string") {
    return {
      kind: "string",
      maxLength: typeof schema.maxLength === "number" ? schema.maxLength : undefined,
    };
  }
  return { kind: "json" };
}

export type ParsedValue = { ok: true; value: unknown } | { ok: false };

/** The text shown in an input for a stored value. */
export function toEditorText(kind: EditorKind, value: unknown): string {
  if (kind.kind === "json") return JSON.stringify(value ?? null, null, 2);
  if (value === null || value === undefined) return "";
  return String(value);
}

/** The JSON value for the text typed into an editor, or `{ ok: false }` when it cannot be one. */
export function parseEditorText(kind: EditorKind, text: string): ParsedValue {
  switch (kind.kind) {
    case "boolean":
      return text === "true" ? { ok: true, value: true } : text === "false" ? { ok: true, value: false } : { ok: false };
    case "enum":
      return kind.options.includes(text) ? { ok: true, value: text } : { ok: false };
    case "string":
      return { ok: true, value: text };
    case "number": {
      if (text.trim() === "") return { ok: false };
      const n = Number(text);
      if (!Number.isFinite(n)) return { ok: false };
      if (kind.integer && !Number.isInteger(n)) return { ok: false };
      if (kind.min !== undefined && n < kind.min) return { ok: false };
      if (kind.max !== undefined && n > kind.max) return { ok: false };
      return { ok: true, value: n };
    }
    case "json":
      try {
        return { ok: true, value: JSON.parse(text) as unknown };
      } catch {
        return { ok: false };
      }
  }
}

/** One line for tables: booleans and numbers as is, everything else as compact JSON. */
export function summarizeValue(value: unknown, max = 60): string {
  const text = typeof value === "string" ? value : JSON.stringify(value);
  const safe = text ?? "null";
  return safe.length > max ? `${safe.slice(0, max - 1)}…` : safe;
}
