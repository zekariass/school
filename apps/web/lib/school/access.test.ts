import { describe, expect, it } from "vitest";
import { isStudentOnly, isTeacherOnly, navItems, schoolAccess, type MeLike } from "./access";

const ADMIN = [
  "school.read",
  "school.manage",
  "school.settings.manage",
  "school.academic.manage",
  "school.staff.manage",
  "school.students.manage",
  "class.manage",
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
      "academic",
      "settings",
      "features",
    ]);
  });

  it("gives teachers the dashboard and their classes only", () => {
    expect(navItems(schoolAccess(teacher, "sunrise"))).toEqual(["dashboard", "myClasses"]);
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

  it("keeps a teacher who also administers classes on the full Classes section", () => {
    const both: MeLike = {
      scopes: [{ scope: { type: "SCHOOL" }, schoolSlug: "sunrise", permissions: ["class.manage"] }],
      memberships: [{ type: "TEACHER", schoolSlug: "sunrise" }],
    };
    expect(navItems(schoolAccess(both, "sunrise"))).toEqual(["dashboard", "classes"]);
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
