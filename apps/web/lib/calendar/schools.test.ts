import { describe, expect, it } from "vitest";
import { calendarSchools, calendarWorkSchools, isCalendarGuardian, type CalendarPeople } from "./schools";

const A = { schoolId: "a", schoolName: "Sunrise", schoolSlug: "sunrise" };
const B = { schoolId: "b", schoolName: "Moon", schoolSlug: "moon" };

describe("calendar schools", () => {
  const teacherAndParent: CalendarPeople = { memberships: [{ type: "TEACHER", ...A }, { type: "GUARDIAN", ...B }, { type: "GUARDIAN", ...B }], scopes: [] };

  it("lists every school the person belongs to once", () => {
    expect(calendarSchools(teacherAndParent)).toEqual([
      { id: "a", name: "Sunrise", slug: "sunrise" },
      { id: "b", name: "Moon", slug: "moon" },
    ]);
  });

  it("offers school events only where the person works", () => {
    expect(calendarWorkSchools(teacherAndParent)).toEqual([{ id: "a", name: "Sunrise", slug: "sunrise" }]);
    expect(calendarWorkSchools({ memberships: [{ type: "GUARDIAN", ...B }, { type: "STUDENT", ...A }], scopes: [] })).toEqual([]);
  });

  it("counts a school administrator with the calendar permission as working there, without a membership", () => {
    const admin: CalendarPeople = {
      memberships: [],
      scopes: [
        { scope: { type: "SCHOOL", schoolId: "a" }, schoolSlug: "sunrise", schoolName: "Sunrise", permissions: ["school.calendar.manage"] },
        { scope: { type: "SCHOOL", schoolId: "b" }, schoolSlug: "moon", schoolName: "Moon", permissions: ["school.read"] },
        { scope: { type: "PLATFORM" }, permissions: ["school.calendar.manage"] },
      ],
    };
    expect(calendarWorkSchools(admin)).toEqual([{ id: "a", name: "Sunrise", slug: "sunrise" }]);
    expect(calendarSchools(admin).map((s) => s.id)).toEqual(["a", "b"]);
  });

  it("recognises guardians", () => {
    expect(isCalendarGuardian(teacherAndParent)).toBe(true);
    expect(isCalendarGuardian({ memberships: [{ type: "TEACHER", ...A }] })).toBe(false);
  });
});
