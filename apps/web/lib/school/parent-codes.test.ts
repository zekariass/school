import { describe, expect, it } from "vitest";
import { parentSheetForClass, sortedForPrint, toParentSheet } from "./parent-codes";

const issued = [
  { studentId: "s2", childFirstName: "Meron", code: "ahr4pa4ws9", expiresAt: "2026-12-25T00:00:00Z", maxUses: 4 },
  { studentId: "s1", childFirstName: "Abel", code: "KKKKKMMMMM", expiresAt: "2026-12-25T00:00:00Z", maxUses: 4 },
];

describe("toParentSheet", () => {
  it("formats the codes as printed", () => {
    const sheet = toParentSheet("c1", "Grade 3A", issued, 1);
    expect(sheet.codes.map((c) => c.code)).toEqual(["AHR4P-A4WS9", "KKKKK-MMMMM"]);
    expect(sheet).toMatchObject({ classId: "c1", className: "Grade 3A", issuedAt: 1 });
  });
});

describe("parentSheetForClass", () => {
  it("only answers for the class it was issued for", () => {
    const sheet = toParentSheet("c1", "Grade 3A", issued, 1);
    expect(parentSheetForClass(sheet, "c1")).toBe(sheet);
    expect(parentSheetForClass(sheet, "c2")).toBeNull();
    expect(parentSheetForClass(null, "c1")).toBeNull();
    expect(parentSheetForClass({ ...sheet, codes: [] }, "c1")).toBeNull();
  });
});

describe("sortedForPrint", () => {
  it("sorts by first name without changing the input", () => {
    const sheet = toParentSheet("c1", "Grade 3A", issued, 1);
    expect(sortedForPrint(sheet.codes).map((c) => c.childFirstName)).toEqual(["Abel", "Meron"]);
    expect(sheet.codes[0]?.childFirstName).toBe("Meron");
  });
});
