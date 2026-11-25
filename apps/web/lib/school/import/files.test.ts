import { describe, expect, it } from "vitest";
import type { PurposePolicy } from "@/lib/media/policies";
import { checkImportFile, CSV_MIME, errorReportName, importMimeType, templateName, XLSX_MIME } from "./files";

const MB = 1024 * 1024;
const policy: PurposePolicy = {
  purpose: "IMPORT_FILE",
  contextTypes: ["SCHOOL"],
  maxBytesByMimeType: { [CSV_MIME]: 10 * MB, "text/plain": 10 * MB, "application/vnd.ms-excel": 10 * MB, [XLSX_MIME]: 10 * MB },
};

describe("importMimeType", () => {
  it("decides the type from the name, whatever the browser says", () => {
    expect(importMimeType("students.csv")).toBe(CSV_MIME);
    expect(importMimeType("Students.CSV")).toBe(CSV_MIME);
    expect(importMimeType("roster.xlsx")).toBe(XLSX_MIME);
  });

  it("refuses the formats the API cannot read", () => {
    expect(importMimeType("old.xls")).toBeNull();
    expect(importMimeType("macros.xlsm")).toBeNull();
    expect(importMimeType("notes.txt")).toBeNull();
    expect(importMimeType("students")).toBeNull();
  });
});

describe("checkImportFile", () => {
  it("accepts a csv with an empty browser type and a normal workbook", () => {
    expect(checkImportFile({ name: "a.csv", size: 100 }, policy)).toBeNull();
    expect(checkImportFile({ name: "a.xlsx", size: 5 * MB }, policy)).toBeNull();
  });

  it("explains what is wrong", () => {
    expect(checkImportFile({ name: "a.xls", size: 100 }, policy)).toEqual({ code: "extension" });
    expect(checkImportFile({ name: "a.csv", size: 0 }, policy)).toEqual({ code: "empty" });
    expect(checkImportFile({ name: "a.csv", size: 11 * MB }, policy)).toEqual({ code: "size", maxMb: 10 });
  });
});

describe("names", () => {
  it("names the error report after the file", () => {
    expect(errorReportName("students 2026.csv")).toBe("students 2026-errors.csv");
    expect(errorReportName("C:\\docs\\roster.xlsx")).toBe("roster-errors.csv");
    expect(errorReportName("")).toBe("import-errors.csv");
  });

  it("names templates", () => {
    expect(templateName("GRADE_LEVELS", "csv")).toBe("grade-levels-template.csv");
    expect(templateName("STUDENTS", "xlsx")).toBe("students-template.xlsx");
  });
});
