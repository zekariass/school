import { describe, expect, it } from "vitest";
import { candidatesOf, defaultFilter, isOpenDuplicate, mergeRows, resolutionOf, rowLabel, statusParam } from "./rows";
import type { ImportRow } from "./state";

const row = (over: Partial<ImportRow> = {}): ImportRow => ({
  id: "r1",
  rowNumber: 2,
  status: "VALID",
  errors: [],
  warnings: [],
  duplicateCandidates: [],
  ...over,
});

describe("filters", () => {
  it("sends no status for 'all'", () => {
    expect(statusParam("ALL")).toBeUndefined();
    expect(statusParam("DUPLICATE")).toBe("DUPLICATE");
  });

  it("opens on what needs attention: duplicates, then problems, then everything", () => {
    expect(defaultFilter({ invalid: 3, duplicates: 1 })).toBe("DUPLICATE");
    expect(defaultFilter({ invalid: 3, duplicates: 0 })).toBe("INVALID");
    expect(defaultFilter({ invalid: 0, duplicates: 0 })).toBe("ALL");
    expect(defaultFilter(undefined)).toBe("ALL");
  });
});

describe("rowLabel", () => {
  it("names a student or staff row from the understood name, Ethiopic included", () => {
    const r = row({ normalized: { givenName: "አልማዝ", middleName: "ታደሰ", familyName: "" } });
    expect(rowLabel(r, "STUDENTS")).toBe("አልማዝ ታደሰ");
    expect(rowLabel(r, "STAFF")).toBe("አልማዝ ታደሰ");
  });

  it("uses the field each type is known by", () => {
    expect(rowLabel(row({ normalized: { className: "8A" } }), "CLASSES")).toBe("8A");
    expect(rowLabel(row({ normalized: { name: "Grade 1" } }), "GRADE_LEVELS")).toBe("Grade 1");
    expect(rowLabel(row({ normalized: { studentName: "Abebe", className: "8A" } }), "ENROLLMENTS")).toBe("Abebe → 8A");
  });

  it("falls back to the first filled cell, then to nothing", () => {
    expect(rowLabel(row({ cells: ["", " Abebe ", "x"] }), "STUDENTS")).toBe("Abebe");
    expect(rowLabel(row(), "STUDENTS")).toBe("");
  });
});

describe("candidatesOf", () => {
  it("reads the loose candidate maps defensively", () => {
    const candidates = candidatesOf(
      row({
        duplicateCandidates: [
          { studentId: "s1", displayName: "Almaz T", dateOfBirth: "2013-05-14", studentNumber: "A1", similarity: 0.82, birthDate: "SAME", classes: ["8A", 3] },
          { displayName: "no id" },
          { studentId: "s2", similarity: "high", birthDate: "WITHIN_A_YEAR" },
        ],
      }),
    );
    expect(candidates).toHaveLength(2);
    expect(candidates[0]).toEqual({
      studentId: "s1",
      displayName: "Almaz T",
      dateOfBirth: "2013-05-14",
      studentNumber: "A1",
      similarity: 0.82,
      sameBirthDate: true,
      classes: ["8A"],
    });
    expect(candidates[1]).toMatchObject({ studentId: "s2", similarity: 0, sameBirthDate: false, classes: [] });
  });
});

describe("decisions", () => {
  it("knows a decided duplicate from an open one", () => {
    expect(resolutionOf(row({ resolution: "LINK" }))).toBe("LINK");
    expect(resolutionOf(row({ resolution: "weird" }))).toBeNull();
    expect(isOpenDuplicate(row({ status: "DUPLICATE" }))).toBe(true);
    expect(isOpenDuplicate(row({ status: "VALID", resolution: "CREATE_NEW" }))).toBe(false);
  });
});

describe("mergeRows", () => {
  it("keeps pages in order and drops a row that appears twice", () => {
    const merged = mergeRows([[row({ id: "a" }), row({ id: "b" })], [row({ id: "b" }), row({ id: "c" })]]);
    expect(merged.map((r) => r.id)).toEqual(["a", "b", "c"]);
  });
});
