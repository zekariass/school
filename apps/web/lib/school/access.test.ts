import { describe, expect, it } from "vitest";
import { isStudentOnly, isTeacherOnly, navItems, schoolAccess, type MeLike } from "./access";

const ADMIN = [
  "school.read",
  "school.manage",
  "school.settings.manage",
  "school.academic.manage",
  "school.staff.manage",
  "school.students.manage",
  "school.import.manage",
  "class.manage",
  "school.guardians.manage",
  "school.policies.manage",
];

const schoolAdmin: MeLike = { scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ADMIN }] };
const teacher: MeLike = { scopes: [], memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }] };
const student: MeLike = { scopes: [], memberships: [{ type: "STUDENT", schoolSlug: "sunrise" }] };

describe("navItems", () => {
  it("gives administrators every section", () => {
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).toEqual([
      "dashboard",
      "classes",
      "students",
      "teachers",
      "import",
      "academic",
      "families",
      "policies",
      "settings",
      "features",
    ]);
  });

  it("shows the import section only with school.import.manage", () => {
    const withImport: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.import.manage"] }],
    };
    expect(navItems(schoolAccess(withImport, "sunrise"))).toEqual(["dashboard", "import"]);
    const campusAdmin: MeLike = {
      scopes: [{ scope: { type: "CAMPUS" }, schoolSlug: "sunrise", permissions: ["school.read", "class.manage"] }],
    };
    expect(navItems(schoolAccess(campusAdmin, "sunrise"))).not.toContain("import");
    expect(navItems(schoolAccess(teacher, "sunrise"))).not.toContain("import");
    expect(navItems(schoolAccess(withImport, "moon"))).not.toContain("import");
  });

  it("gives teachers the dashboard, their classes and the families queue", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toEqual(["dashboard", "myClasses", "families", "attendance", "points"]);
  });

  it("shows Policies only with the policies permission", () => {
    const guardianAdmin: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.read", "school.guardians.manage"] }],
    };
    expect(navItems(schoolAccess(guardianAdmin, "sunrise"))).toEqual(["dashboard", "families", "features"]);
  });

  it("shows a campus administrator the classes and the read-only features", () => {
    const campus: MeLike = {
      scopes: [{ scope: { type: "CAMPUS" }, schoolSlug: "sunrise", permissions: ["school.read", "class.manage"] }],
    };
    expect(navItems(schoolAccess(campus, "sunrise"))).toEqual(["dashboard", "classes", "features"]);
  });

  it("shows only the sections whose permission is held", () => {
    const registrar: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.read", "school.students.manage"] }],
    };
    expect(navItems(schoolAccess(registrar, "sunrise"))).toEqual(["dashboard", "students", "features"]);
  });

  it("shows Attendance to attendance readers and managers, and teachers", () => {
    const reader: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.attendance.read"] }],
    };
    expect(navItems(schoolAccess(reader, "sunrise"))).toEqual(["dashboard", "attendance"]);
    const manager: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["school.attendance.manage"] }],
    };
    expect(navItems(schoolAccess(manager, "sunrise"))).toContain("attendance");
    expect(navItems(schoolAccess(reader, "moon"))).not.toContain("attendance");
    expect(navItems(schoolAccess(schoolAdmin, "sunrise"))).not.toContain("attendance");
  });

  it("keeps a teacher who also administers classes on the full Classes section", () => {
    const both: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["class.manage"] }],
      memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }],
    };
    expect(navItems(schoolAccess(both, "sunrise"))).toEqual(["dashboard", "classes", "families", "attendance", "points"]);
  });
});

describe("schoolAccess", () => {
  it("ignores grants and memberships of other schools", () => {
    const access = schoolAccess(schoolAdmin, "moon");
    expect(access.permissions.size).toBe(0);
    expect(schoolAccess(teacher, "moon").isTeacher).toBe(false);
  });

  it("applies grants above school level to every school", () => {
    const platform: MeLike = { scopes: [{ scope: { type: "PLATFORM" }, permissions: ADMIN }] };
    expect(navItems(schoolAccess(platform, "any-school"))).toContain("academic");
  });
});

describe("student-only and teacher-only", () => {
  it("recognizes a student without anything else", () => {
    expect(isStudentOnly(schoolAccess(student, "sunrise"))).toBe(true);
    expect(isStudentOnly(schoolAccess(teacher, "sunrise"))).toBe(false);
    expect(isStudentOnly(schoolAccess(schoolAdmin, "sunrise"))).toBe(false);
  });

  it("recognizes a plain teacher", () => {
    expect(isTeacherOnly(schoolAccess(teacher, "sunrise"))).toBe(true);
    expect(isTeacherOnly(schoolAccess(schoolAdmin, "sunrise"))).toBe(false);
  });
});
