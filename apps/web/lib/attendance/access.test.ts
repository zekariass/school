import { describe, expect, it } from "vitest";
import { attendancePageAccess, canSeeChildAttendance, classAttendanceAbilities } from "./access";
import { attendanceSettingId } from "./settings";
import { isKnownSlot } from "./slots";

const none = new Set<string>();
const active = { status: "ACTIVE" as const };
const teacher = (role: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT") => ({ ...active, myMembership: "TEACHER" as const, myTeacherRole: role });

describe("classAttendanceAbilities", () => {
  it("lets the lead, co and subject teachers take the roll and decide on absence reports", () => {
    for (const role of ["LEAD", "CO_TEACHER", "SUBJECT_TEACHER"] as const) {
      expect(classAttendanceAbilities(teacher(role), none)).toEqual({ canView: true, canTake: true, canDecide: true, seesAbsenceDetails: true });
    }
  });

  it("lets assistants take the roll (the API checks the school setting) but not decide", () => {
    const a = classAttendanceAbilities(teacher("ASSISTANT"), none);
    expect(a.canTake).toBe(true);
    expect(a.canDecide).toBe(false);
    expect(a.seesAbsenceDetails).toBe(false);
  });

  it("lets class managers take the roll but not read or decide on absence reports", () => {
    const a = classAttendanceAbilities(active, new Set(["class.manage"]));
    expect(a).toEqual({ canView: true, canTake: true, canDecide: false, seesAbsenceDetails: false });
  });

  it("lets attendance managers do everything and readers only look", () => {
    expect(classAttendanceAbilities(active, new Set(["school.attendance.manage"]))).toEqual({
      canView: true,
      canTake: true,
      canDecide: true,
      seesAbsenceDetails: true,
    });
    expect(classAttendanceAbilities(active, new Set(["school.attendance.read"]))).toEqual({
      canView: true,
      canTake: false,
      canDecide: false,
      seesAbsenceDetails: false,
    });
  });

  it("freezes an archived class", () => {
    const archived = { status: "ARCHIVED" as const, myMembership: "TEACHER" as const, myTeacherRole: "LEAD" as const };
    expect(classAttendanceAbilities(archived, none).canTake).toBe(false);
    expect(classAttendanceAbilities(archived, none).canView).toBe(true);
  });

  it("gives students and bystanders nothing", () => {
    expect(classAttendanceAbilities({ ...active, myMembership: "STUDENT" }, none).canView).toBe(false);
    expect(classAttendanceAbilities(active, none).canView).toBe(false);
  });
});

describe("attendancePageAccess", () => {
  it("shows the page to teachers, readers and managers", () => {
    expect(attendancePageAccess(none, true)).toMatchObject({ canSee: true, canSeeStudents: true, canSeeSchoolWide: false });
    expect(attendancePageAccess(new Set(["school.attendance.read"]), false)).toMatchObject({ canSee: true, canSeeStudents: true, canSeeSchoolWide: true });
    expect(attendancePageAccess(new Set(["school.attendance.manage"]), false)).toMatchObject({ canSee: true, canSeeStudents: true, canSeeSchoolWide: true });
    expect(attendancePageAccess(new Set(["class.manage"]), false)).toMatchObject({ canSee: false, canSeeStudents: false, canSeeSchoolWide: false });
  });
});

describe("guardians and settings", () => {
  it("shows a child's attendance only to guardians who may view academics", () => {
    expect(canSeeChildAttendance({ canViewAcademics: true })).toBe(true);
    expect(canSeeChildAttendance({ canViewAcademics: false })).toBe(false);
  });

  it("knows the four attendance settings a school can change", () => {
    expect(attendanceSettingId("attendance.slots")).toBe("slots");
    expect(attendanceSettingId("attendance.assistants_can_take")).toBe("assistants");
    expect(attendanceSettingId("attendance.lock_after_hours")).toBe("lockAfter");
    expect(attendanceSettingId("attendance.notify_guardians.statuses")).toBe("notify");
    expect(attendanceSettingId("engagement.badges.enabled")).toBeNull();
    expect(attendanceSettingId("toString")).toBeNull();
  });

  it("knows the roll calls", () => {
    for (const slot of ["DAY", "AM", "PM", "P1", "P8"]) expect(isKnownSlot(slot)).toBe(true);
    expect(isKnownSlot("P9")).toBe(false);
    expect(isKnownSlot("")).toBe(false);
  });
});
