import { describe, expect, it } from "vitest";
import {
  applyProfile,
  cleanMapping,
  columnLetter,
  delimiterIdOf,
  delimiterValue,
  encodingIdOf,
  hasReusedColumn,
  missingFields,
  readingQuery,
  requirementOf,
  sampleValues,
  setColumn,
} from "./mapping";

describe("columnLetter", () => {
  it("names columns like a spreadsheet", () => {
    expect(columnLetter(0)).toBe("A");
    expect(columnLetter(25)).toBe("Z");
    expect(columnLetter(26)).toBe("AA");
    expect(columnLetter(27)).toBe("AB");
    expect(columnLetter(701)).toBe("ZZ");
    expect(columnLetter(702)).toBe("AAA");
  });
});

describe("setColumn", () => {
  it("maps a field and takes the column from the field that had it", () => {
    const next = setColumn({ given_name: 0, father_name: 1 }, "father_name", 0);
    expect(next).toEqual({ father_name: 0 });
    expect(hasReusedColumn(next)).toBe(false);
  });

  it("clears a field with null and does not touch the others", () => {
    expect(setColumn({ a: 0, b: 1 }, "a", null)).toEqual({ b: 1 });
  });

  it("does not change its input", () => {
    const before = { a: 0 };
    setColumn(before, "b", 1);
    expect(before).toEqual({ a: 0 });
  });
});

describe("cleanMapping", () => {
  it("drops entries that point outside the file's columns", () => {
    expect(cleanMapping({ a: 0, b: 5, c: -1 }, 3)).toEqual({ a: 0 });
    expect(cleanMapping(undefined, 3)).toEqual({});
  });
});

describe("missingFields and requirementOf", () => {
  const required = ["given_name", "father_name", "phone|contact"];

  it("reports required fields and 'one of' groups that are not satisfied", () => {
    expect(missingFields(required, {})).toEqual(required);
    expect(missingFields(required, { given_name: 0, father_name: 1, contact: 2 })).toEqual([]);
    expect(missingFields(required, { given_name: 0, phone: 3 })).toEqual(["father_name"]);
  });

  it("tells required, one-of and optional fields apart", () => {
    expect(requirementOf({ key: "given_name", label: "", required: true }, required)).toBe("required");
    expect(requirementOf({ key: "phone", label: "", required: false, oneOf: "contact" }, required)).toBe("oneOf");
    expect(requirementOf({ key: "job_title", label: "", required: false }, required)).toBe("optional");
  });
});

describe("applyProfile", () => {
  const headers = ["Given Name", " Father  name ", "Phone", "Notes"];

  it("matches saved headings to this file's columns, ignoring case and spacing", () => {
    const { mapping, unmatched } = applyProfile({ given_name: "given name", father_name: "father name", phone: "Phone" }, headers);
    expect(mapping).toEqual({ given_name: 0, father_name: 1, phone: 2 });
    expect(unmatched).toEqual([]);
  });

  it("reports the fields whose heading is not in the file and keeps the base mapping", () => {
    const { mapping, unmatched } = applyProfile({ given_name: "First name", phone: "Phone" }, headers, { given_name: 0 });
    expect(mapping).toEqual({ given_name: 0, phone: 2 });
    expect(unmatched).toEqual(["given_name"]);
  });

  it("matches Amharic headings", () => {
    const { mapping } = applyProfile({ given_name: "ስም" }, ["ስም", "የአባት ስም"]);
    expect(mapping).toEqual({ given_name: 0 });
  });

  it("never gives one column to two fields", () => {
    const { mapping } = applyProfile({ a: "x", b: "x" }, ["x"]);
    expect(hasReusedColumn(mapping)).toBe(false);
  });
});

describe("sampleValues", () => {
  const rows = [
    { cells: ["# example", ""], example: true },
    { cells: ["Abebe", "Kebede"], example: false },
    { cells: ["", "Almaz"], example: false },
    { cells: ["Sara", "Tesfaye"], example: false },
  ];

  it("returns the first non-empty values and skips example rows", () => {
    expect(sampleValues(rows, 0)).toEqual(["Abebe", "Sara"]);
    expect(sampleValues(rows, 1, 3)).toEqual(["Kebede", "Almaz", "Tesfaye"]);
  });
});

describe("reading options", () => {
  it("turns what the API detected into the dropdown choices", () => {
    expect(delimiterIdOf(",")).toBe("comma");
    expect(delimiterIdOf(";")).toBe("semicolon");
    expect(delimiterIdOf("\t")).toBe("tab");
    expect(delimiterIdOf(undefined)).toBe("");
    expect(encodingIdOf("utf-8")).toBe("UTF-8");
    expect(encodingIdOf("windows-1252")).toBe("windows-1252");
    expect(encodingIdOf("ISO-8859-2")).toBe("");
  });

  it("sends the single character the API wants, and 'tab' for a tab", () => {
    expect(delimiterValue("semicolon")).toBe(";");
    expect(delimiterValue("tab")).toBe("tab");
    expect(delimiterValue("")).toBe("");
  });

  it("builds the re-read query only once something was changed", () => {
    expect(readingQuery(null)).toBeNull();
    expect(readingQuery({ encoding: "", delimiter: "semicolon", sheet: "" })).toEqual({ encoding: "", delimiter: ";", sheet: "" });
  });
});
