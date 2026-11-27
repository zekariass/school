import { describe, expect, it } from "vitest";
import { ApiRequestError } from "@/lib/api-error";
import { attendancePageAccess } from "./access";
import {
  buildCorrection,
  buildDayRequest,
  canMark,
  clearStaffDraft,
  correctionChanges,
  correctionErrors,
  correctionFormOf,
  dayCounts,
  draftCount,
  isMonth,
  isVersionConflict,
  monthOf,
  parseStaffMinutes,
  setStaffMinutes,
  setStaffNote,
  setStaffStatus,
  staffCsvFileName,
  staffCsvPath,
  staffDailyQuery,
  staffFailure,
  staffMarkValid,
  staffMonthlyQuery,
  unmarkedCount,
  type StaffDayEntry,
  type StaffRecord,
} from "./staff";

const problem = (code: string, status: number) => new ApiRequestError(status, { type: "about:blank", title: "t", status, code } as never);

const record = (over: Partial<StaffRecord> = {}): StaffRecord =>
  ({ id: "r1", staffId: "a", staffName: "Abebe", date: "2026-11-25", status: "ABSENT", method: "MANUAL", recordedAt: "2026-11-25T07:00:00Z", recordedBy: "u", version: 2, ...over }) as StaffRecord;

const entry = (staffId: string, r?: Partial<StaffRecord>): StaffDayEntry => ({ staffId, displayName: staffId.toUpperCase(), staffType: "TEACHER", ...(r ? { record: record({ staffId, ...r }) } : {}) });

describe("who sees the staff tab", () => {
  const perms = (...p: string[]) => new Set(p);

  it("shows it to a manager (who may mark and correct) and to a reader (who only looks)", () => {
    const manager = attendancePageAccess(perms("school.staff_attendance.manage"), false);
    expect(manager).toMatchObject({ canSee: true, canSeeStaff: true, canManageStaff: true, canSeeStudents: false });
    const reader = attendancePageAccess(perms("school.staff_attendance.read"), false);
    expect(reader).toMatchObject({ canSee: true, canSeeStaff: true, canManageStaff: false });
  });

  it("never shows it to a teacher, however much of the student attendance they hold", () => {
    const teacher = attendancePageAccess(perms(), true);
    expect(teacher).toMatchObject({ canSee: true, canSeeStudents: true, canSeeStaff: false, canManageStaff: false });
    const attendanceManager = attendancePageAccess(perms("school.attendance.manage", "school.attendance.read"), true);
    expect(attendanceManager.canSeeStaff).toBe(false);
  });

  it("gives an administrator of another school nothing here (their grants do not name this school)", () => {
    // `schoolAccess` filters the grants to this school before this runs: no permissions arrive.
    expect(attendancePageAccess(perms(), false)).toEqual({ canSee: false, canSeeStudents: false, canSeeSchoolWide: false, canSeeStaff: false, canManageStaff: false });
  });
});

describe("the API's refusals", () => {
  it("tells a switched-off setting from a plan without attendance and from no access", () => {
    expect(staffFailure(problem("staff_attendance.disabled", 403))).toBe("disabled");
    expect(staffFailure(problem("attendance.feature_disabled", 403))).toBe("featureOff");
    expect(staffFailure(problem("staff_attendance.access_denied", 403))).toBe("denied");
    expect(staffFailure(problem("school.not_found", 404))).toBe("denied");
    expect(staffFailure(problem("staff_attendance.record_not_found", 404))).toBe("denied");
    expect(staffFailure(problem("internal", 500))).toBe("other");
    expect(staffFailure(new Error("x"))).toBe("other");
  });

  it("recognises a version conflict", () => {
    expect(isVersionConflict(problem("staff_attendance.version_conflict", 409))).toBe(true);
    expect(isVersionConflict(problem("staff_attendance.date_in_future", 409))).toBe(false);
  });
});

describe("marking a day", () => {
  it("lets a person be marked only while they have no record", () => {
    expect(canMark(entry("a"))).toBe(true);
    expect(canMark(entry("b", { status: "PRESENT" }))).toBe(false);
  });

  it("keeps minutes only with LATE and drops them when the status changes", () => {
    let drafts = setStaffStatus({}, "a", "LATE");
    drafts = setStaffMinutes(drafts, "a", 15);
    expect(drafts.a).toEqual({ status: "LATE", minutesLate: 15 });
    drafts = setStaffStatus(drafts, "a", "ABSENT");
    expect(drafts.a).toEqual({ status: "ABSENT" });
    drafts = setStaffStatus(drafts, "a", "LATE");
    expect(drafts.a).toEqual({ status: "LATE" });
  });

  it("keeps the note across status changes and trims it when it is sent", () => {
    let drafts = setStaffStatus({}, "a", "PRESENT");
    drafts = setStaffNote(drafts, "a", "  doctor  ");
    drafts = setStaffStatus(drafts, "a", "HALF_DAY");
    expect(drafts.a).toEqual({ status: "HALF_DAY", note: "doctor" });
    expect(setStaffNote({}, "zz", "x")).toEqual({});
  });

  it("clears a draft", () => {
    const drafts = clearStaffDraft(setStaffStatus({}, "a", "ABSENT"), "a");
    expect(draftCount(drafts)).toBe(0);
  });

  it("accepts minutes 1..600 with LATE only and a note of at most 200 characters", () => {
    expect(staffMarkValid({ status: "LATE", minutesLate: 600 })).toBe(true);
    expect(staffMarkValid({ status: "LATE", minutesLate: 601 })).toBe(false);
    expect(staffMarkValid({ status: "LATE", minutesLate: 0 })).toBe(false);
    expect(staffMarkValid({ status: "ABSENT", minutesLate: 5 })).toBe(false);
    expect(staffMarkValid({ status: "ABSENT", note: "x".repeat(200) })).toBe(true);
    expect(staffMarkValid({ status: "ABSENT", note: "x".repeat(201) })).toBe(false);
  });

  it("builds the request from the marks of people without a record, in roll order", () => {
    const entries = [entry("a"), entry("b", { status: "PRESENT" }), entry("c")];
    const drafts = { c: { status: "LATE" as const, minutesLate: 10, note: " late bus " }, b: { status: "ABSENT" as const }, a: { status: "ABSENT" as const } };
    expect(buildDayRequest(entries, drafts, false)).toEqual({
      records: [
        { staffId: "a", status: "ABSENT" },
        { staffId: "c", status: "LATE", minutesLate: 10, note: "late bus" },
      ],
    });
  });

  it("asks to mark the rest present, with or without marks of its own", () => {
    expect(buildDayRequest([entry("a")], {}, true)).toEqual({ markRestPresent: true });
    expect(buildDayRequest([entry("a")], { a: { status: "EXCUSED" } }, true)).toEqual({ records: [{ staffId: "a", status: "EXCUSED" }], markRestPresent: true });
  });

  it("sends nothing when there is nothing to record", () => {
    expect(buildDayRequest([entry("a"), entry("b", { status: "PRESENT" })], { b: { status: "ABSENT" } }, false)).toBeNull();
  });

  it("counts the day", () => {
    const entries = [entry("a"), entry("b", { status: "PRESENT" }), entry("c", { status: "HALF_DAY" }), entry("d", { status: "LATE" })];
    expect(dayCounts(entries)).toEqual({ staff: 4, marked: 3, unmarked: 1, present: 1, absent: 0, late: 1, halfDay: 1, excused: 0 });
    expect(unmarkedCount(entries)).toBe(1);
  });
});

describe("corrections", () => {
  const stored = record({ status: "LATE", minutesLate: 10, note: "bus" });

  it("starts from the stored record", () => {
    expect(correctionFormOf(stored)).toEqual({ status: "LATE", minutes: "10", note: "bus", reason: "" });
    expect(correctionFormOf(record({ note: undefined }))).toEqual({ status: "ABSENT", minutes: "", note: "", reason: "" });
  });

  it("parses minutes 1..600 only", () => {
    expect(parseStaffMinutes("15")).toBe(15);
    expect(parseStaffMinutes(" 600 ")).toBe(600);
    for (const bad of ["", "0", "601", "-1", "1.5", "abc"]) expect(parseStaffMinutes(bad)).toBeUndefined();
  });

  it("requires a reason", () => {
    const form = { ...correctionFormOf(stored), status: "PRESENT" as const };
    expect(correctionErrors(stored, form).reason).toBe("required");
    expect(correctionErrors(stored, { ...form, reason: "   " }).reason).toBe("required");
    expect(correctionErrors(stored, { ...form, reason: "x".repeat(201) }).reason).toBe("tooLong");
    expect(correctionErrors(stored, { ...form, reason: "Checked the gate log" })).toEqual({});
  });

  it("refuses a correction that changes nothing", () => {
    const form = { ...correctionFormOf(stored), reason: "oops" };
    expect(correctionChanges(stored, form)).toBe(false);
    expect(correctionErrors(stored, form)).toEqual({ nothing: "nothingChanged" });
    expect(correctionChanges(stored, { ...form, minutes: "20" })).toBe(true);
  });

  it("checks the minutes and the note", () => {
    const form = { ...correctionFormOf(stored), reason: "r", minutes: "700" };
    expect(correctionErrors(stored, form).minutes).toBe("invalid");
    expect(correctionErrors(stored, { ...form, minutes: "5", note: "x".repeat(201) }).note).toBe("tooLong");
    // Minutes of a status that is not LATE are not checked: they are not sent.
    expect(correctionErrors(stored, { ...form, status: "ABSENT" }).minutes).toBeUndefined();
  });

  it("builds the request with the version the manager read and the reason", () => {
    const form = { ...correctionFormOf(stored), status: "PRESENT" as const, reason: "  Gate log shows 07:55  " };
    expect(buildCorrection(stored, form)).toEqual({ status: "PRESENT", reason: "Gate log shows 07:55", expectedVersion: 2 });
  });

  it("sends the note only when it changed: omitted keeps it, an empty text clears it", () => {
    const base = { ...correctionFormOf(stored), reason: "r", minutes: "20" };
    expect(buildCorrection(stored, base)).toEqual({ status: "LATE", minutesLate: 20, reason: "r", expectedVersion: 2 });
    expect(buildCorrection(stored, { ...base, note: "new" })).toMatchObject({ note: "new" });
    expect(buildCorrection(stored, { ...base, note: "" })).toMatchObject({ note: "" });
  });

  it("cannot be built for a record without a version", () => {
    expect(buildCorrection(record({ version: undefined }), { ...correctionFormOf(stored), reason: "r" })).toBeNull();
  });
});

describe("summaries and their CSV", () => {
  it("knows a month", () => {
    expect(isMonth("2026-11")).toBe(true);
    for (const bad of ["2026-13", "2026-00", "2026-1", "202611", "", null, undefined, "2026-11-01"]) expect(isMonth(bad)).toBe(false);
    expect(monthOf("2026-11-25")).toBe("2026-11");
  });

  it("leaves malformed filters out of the queries", () => {
    expect(staffDailyQuery("2026-11-01", "2026-11-30")).toEqual({ from: "2026-11-01", to: "2026-11-30" });
    expect(staffDailyQuery("", "nope")).toEqual({});
    expect(staffMonthlyQuery("2026-11")).toEqual({ month: "2026-11" });
    expect(staffMonthlyQuery("junk")).toEqual({});
  });

  it("builds the CSV path under the reports", () => {
    expect(staffCsvPath("monthly", { month: "2026-11" })).toBe("monthly.csv?month=2026-11");
    expect(staffCsvPath("daily", { from: "2026-11-01", to: "2026-11-30" })).toBe("daily.csv?from=2026-11-01&to=2026-11-30");
    expect(staffCsvPath("daily", {})).toBe("daily.csv");
  });

  it("names the files", () => {
    expect(staffCsvFileName("monthly", { month: "2026-11" })).toBe("staff-attendance-2026-11.csv");
    expect(staffCsvFileName("monthly", {})).toBe("staff-attendance-month.csv");
    expect(staffCsvFileName("daily", { from: "2026-11-01", to: "2026-11-30" })).toBe("staff-attendance-daily-2026-11-01_2026-11-30.csv");
    expect(staffCsvFileName("daily", {})).toBe("staff-attendance-daily.csv");
  });
});
