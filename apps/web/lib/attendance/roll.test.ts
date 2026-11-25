import { describe, expect, it } from "vitest";
import {
  applyResult,
  buildResolve,
  buildWrite,
  conflictsOf,
  countsOf,
  defaultMark,
  draftInputs,
  isEmptyWrite,
  isUnsaved,
  isWriteResult,
  keepTheirs,
  mineInputs,
  normalizeMark,
  pendingEditCount,
  sameMark,
  setMark,
  setMinutes,
  setNote,
  setStatus,
  shownMark,
  summarize,
  unsavedStudents,
  type Conflicts,
  type Drafts,
  type RecordView,
  type RollStudent,
  type RollView,
  type WriteResult,
} from "./roll";

const record = (status: RecordView["status"], version: number, extra: Partial<RecordView> = {}): RecordView => ({
  id: `r-${status}-${version}`,
  status,
  version,
  recordedAt: "2026-11-25T07:00:00Z",
  recordedBy: "teacher",
  source: "WEB",
  ...extra,
});

const student = (id: string, extra: Partial<RollStudent> = {}): RollStudent => ({ studentId: id, displayName: `Student ${id}`, ...extra });

function rollOf(students: RollStudent[], extra: Partial<RollView> = {}): RollView {
  return {
    classId: "class-1",
    date: "2026-11-25",
    slot: "DAY",
    slots: ["DAY"],
    timeZone: "Africa/Addis_Ababa",
    locksAt: "2026-11-27T21:00:00Z",
    students,
    counts: countsOf(students),
    ...extra,
  };
}

describe("what the roll shows", () => {
  it("shows an unmarked student as present, or as the suggestion", () => {
    expect(shownMark(student("a"), {})).toEqual({ status: "PRESENT" });
    expect(defaultMark(student("a", { suggestedStatus: "EXCUSED" }))).toEqual({ status: "EXCUSED" });
    expect(shownMark(student("a", { suggestedStatus: "EXCUSED" }), {})).toEqual({ status: "EXCUSED" });
  });

  it("shows the stored record, and an edit over it", () => {
    const s = student("a", { record: record("LATE", 2, { minutesLate: 10, note: "bus" }) });
    expect(shownMark(s, {})).toEqual({ status: "LATE", minutesLate: 10, note: "bus" });
    const drafts = setStatus({}, s, "ABSENT");
    expect(shownMark(s, drafts)).toEqual({ status: "ABSENT", note: "bus" });
  });

  it("marks defaults and edits as not saved", () => {
    const saved = student("a", { record: record("PRESENT", 0) });
    const open = student("b");
    expect(isUnsaved(saved, {})).toBe(false);
    expect(isUnsaved(open, {})).toBe(true);
    expect(isUnsaved(saved, setStatus({}, saved, "ABSENT"))).toBe(true);
  });
});

describe("editing", () => {
  const a = student("a");
  const b = student("b", { record: record("PRESENT", 0) });

  it("an edit equal to the saved state is no edit", () => {
    expect(pendingEditCount(setStatus({}, a, "PRESENT"))).toBe(0);
    expect(pendingEditCount(setStatus({}, b, "PRESENT"))).toBe(0);
    const edited = setStatus({}, b, "ABSENT");
    expect(pendingEditCount(edited)).toBe(1);
    expect(pendingEditCount(setStatus(edited, b, "PRESENT"))).toBe(0);
  });

  it("an unmarked student whose default is EXCUSED needs an explicit mark to be present", () => {
    const excused = student("c", { suggestedStatus: "EXCUSED" });
    expect(pendingEditCount(setStatus({}, excused, "EXCUSED"))).toBe(0);
    expect(setStatus({}, excused, "PRESENT")).toEqual({ c: { status: "PRESENT" } });
  });

  it("keeps minutes only with LATE", () => {
    let drafts: Drafts = setStatus({}, b, "LATE");
    drafts = setMinutes(drafts, b, 12);
    expect(shownMark(b, drafts)).toEqual({ status: "LATE", minutesLate: 12 });
    drafts = setStatus(drafts, b, "ABSENT");
    expect(shownMark(b, drafts)).toEqual({ status: "ABSENT" });
    drafts = setStatus(drafts, b, "LATE");
    expect(shownMark(b, drafts).minutesLate).toBeUndefined();
    expect(normalizeMark({ status: "EXCUSED", minutesLate: 5 })).toEqual({ status: "EXCUSED" });
  });

  it("trims notes and ignores empty ones", () => {
    const drafts = setNote({}, b, "  parent called  ");
    expect(drafts.b).toEqual({ status: "PRESENT", note: "parent called" });
    expect(pendingEditCount(setNote(drafts, b, "   "))).toBe(0);
    expect(sameMark({ status: "PRESENT", note: " x " }, { status: "PRESENT", note: "x" })).toBe(true);
    expect(sameMark({ status: "LATE", minutesLate: 5 }, { status: "LATE", minutesLate: 6 })).toBe(false);
  });

  it("setMark with minutes cleared removes them", () => {
    const late = student("l", { record: record("LATE", 1, { minutesLate: 8 }) });
    const drafts = setMinutes({}, late, undefined);
    expect(shownMark(late, drafts)).toEqual({ status: "LATE" });
    expect(setMark({}, late, { status: "LATE", minutesLate: 8 })).toEqual({});
  });
});

describe("the request that saves a roll", () => {
  const unmarked = student("a");
  const marked = student("b", { record: record("PRESENT", 3) });
  const roll = rollOf([unmarked, marked], { sessionVersion: 7, sessionId: "session-1" });

  it("sends only the edits, each with the version it was edited from", () => {
    let drafts: Drafts = setStatus({}, marked, "ABSENT");
    drafts = setStatus(drafts, unmarked, "LATE");
    drafts = setMinutes(drafts, unmarked, 15);
    expect(draftInputs(roll, drafts)).toEqual([
      { studentId: "a", status: "LATE", minutesLate: 15 }, // no record yet: no expectedVersion
      { studentId: "b", status: "ABSENT", expectedVersion: 3 },
    ]);
  });

  it("names the roll version it read and the roll call", () => {
    const request = buildWrite({ roll, drafts: setStatus({}, marked, "ABSENT") });
    expect(request).toEqual({
      date: "2026-11-25",
      slot: "DAY",
      baseVersion: 7,
      records: [{ studentId: "b", status: "ABSENT", expectedVersion: 3 }],
    });
  });

  it("leaves baseVersion out while nobody has taken the roll", () => {
    const fresh = rollOf([unmarked]);
    const request = buildWrite({ roll: fresh, drafts: {}, markAllPresent: true });
    expect(request).toEqual({ date: "2026-11-25", slot: "DAY", markAllPresent: true, records: [] });
    expect("baseVersion" in request).toBe(false);
  });

  it("sends the override reason only for a locked roll", () => {
    expect(buildWrite({ roll, drafts: {}, markAllPresent: true, overrideReason: "  fix  " }).overrideReason).toBeUndefined();
    const locked = rollOf([marked], { locked: true, sessionVersion: 2 });
    expect(buildWrite({ roll: locked, drafts: setStatus({}, marked, "ABSENT"), overrideReason: "  parent called  " }).overrideReason).toBe("parent called");
    expect(buildWrite({ roll: locked, drafts: setStatus({}, marked, "ABSENT"), overrideReason: "   " }).overrideReason).toBeUndefined();
  });

  it("can send only some students' edits", () => {
    const drafts = setStatus(setStatus({}, marked, "ABSENT"), unmarked, "LATE");
    const request = buildWrite({ roll, drafts, onlyStudentIds: new Set(["a"]) });
    expect(request.records?.map((r) => r.studentId)).toEqual(["a"]);
  });

  it("knows when there is nothing to send", () => {
    expect(isEmptyWrite(buildWrite({ roll, drafts: {} }))).toBe(true);
    expect(isEmptyWrite(buildWrite({ roll, drafts: {}, markAllPresent: true }))).toBe(false);
    expect(isEmptyWrite(buildWrite({ roll, drafts: setStatus({}, marked, "ABSENT") }))).toBe(false);
  });

  it("lists the students without a record", () => {
    expect(unsavedStudents(roll).map((s) => s.studentId)).toEqual(["a"]);
  });
});

describe("the answer of the API", () => {
  const a = student("a");
  const b = student("b", { record: record("PRESENT", 1) });
  const c = student("c", { record: record("ABSENT", 4) });
  const roll = rollOf([a, b, c], { sessionVersion: 5, sessionId: "s1" });

  const conflictResult: WriteResult = {
    sessionId: "s1",
    classId: "class-1",
    date: "2026-11-25",
    slot: "DAY",
    sessionVersion: 6,
    applied: 1,
    unchanged: 0,
    conflicts: 1,
    outcomes: [
      { studentId: "a", outcome: "APPLIED", record: record("LATE", 0, { minutesLate: 7 }) },
      {
        studentId: "b",
        outcome: "CONFLICT",
        record: record("ABSENT", 2),
        submitted: { status: "LATE", minutesLate: 9, note: "bus", expectedVersion: 1 },
      },
    ],
  };

  it("tells a conflict body from a problem", () => {
    expect(isWriteResult(conflictResult)).toBe(true);
    expect(isWriteResult({ status: 409, code: "attendance.session_locked", title: "Locked" })).toBe(false);
    expect(isWriteResult(null)).toBe(false);
    expect(isWriteResult("x")).toBe(false);
  });

  it("extracts the conflicts with the stored record and the submitted mark", () => {
    expect(conflictsOf(conflictResult)).toEqual([
      { studentId: "b", stored: record("ABSENT", 2), mine: { status: "LATE", minutesLate: 9, note: "bus" } },
    ]);
  });

  it("applies written marks, keeps conflicts out of the drafts and moves the roll version", () => {
    const drafts: Drafts = { ...setStatus({}, a, "LATE"), ...setStatus({}, b, "LATE"), ...setStatus({}, c, "LATE") };
    const applied = applyResult(roll, drafts, {}, conflictResult);
    expect(applied.roll.sessionVersion).toBe(6);
    expect(applied.roll.students.find((s) => s.studentId === "a")?.record?.version).toBe(0);
    // The conflicted student shows what is stored now, and their own mark waits in the conflict list.
    expect(applied.roll.students.find((s) => s.studentId === "b")?.record?.status).toBe("ABSENT");
    expect(Object.keys(applied.conflicts)).toEqual(["b"]);
    // Students not in the answer keep their edit.
    expect(Object.keys(applied.drafts)).toEqual(["c"]);
    expect(applied.roll.counts).toMatchObject({ students: 3, marked: 3, unmarked: 0, late: 1, absent: 2 });
  });

  it("resolves a conflict with 'keep theirs': nothing is sent, the stored mark stays", () => {
    const applied = applyResult(roll, {}, {}, conflictResult);
    const next = keepTheirs(applied.drafts, applied.conflicts, "b");
    expect(next.conflicts).toEqual({});
    expect(shownMark(applied.roll.students.find((s) => s.studentId === "b")!, next.drafts).status).toBe("ABSENT");
  });

  it("resolves a conflict with 'use mine': resends the mark against the stored version", () => {
    const applied = applyResult(roll, {}, {}, conflictResult);
    const request = buildResolve(applied.roll, applied.conflicts, ["b"]);
    expect(request).toEqual({
      date: "2026-11-25",
      slot: "DAY",
      baseVersion: 6,
      records: [{ studentId: "b", status: "LATE", minutesLate: 9, note: "bus", expectedVersion: 2 }],
    });
  });

  it("clears the conflict once 'use mine' was applied, or keeps it when someone changed it again", () => {
    const applied = applyResult(roll, {}, {}, conflictResult);
    const second: WriteResult = {
      ...conflictResult,
      sessionVersion: 7,
      applied: 1,
      conflicts: 0,
      outcomes: [{ studentId: "b", outcome: "APPLIED", record: record("LATE", 3, { minutesLate: 9, note: "bus" }) }],
    };
    const done = applyResult(applied.roll, applied.drafts, applied.conflicts, second);
    expect(done.conflicts).toEqual({});
    expect(done.roll.students.find((s) => s.studentId === "b")?.record?.version).toBe(3);

    const again: WriteResult = {
      ...second,
      applied: 0,
      conflicts: 1,
      outcomes: [{ studentId: "b", outcome: "CONFLICT", record: record("EXCUSED", 3), submitted: { status: "LATE", minutesLate: 9, note: "bus", expectedVersion: 2 } }],
    };
    const still = applyResult(applied.roll, applied.drafts, applied.conflicts, again);
    expect(still.conflicts.b?.stored.version).toBe(3);
    expect(mineInputs(still.conflicts)[0]?.expectedVersion).toBe(3);
  });

  it("can resend only chosen conflicts", () => {
    const conflicts: Conflicts = {
      x: { studentId: "x", stored: record("ABSENT", 1), mine: { status: "PRESENT" } },
      y: { studentId: "y", stored: record("ABSENT", 5), mine: { status: "LATE", minutesLate: 3 } },
    };
    expect(mineInputs(conflicts, ["y"])).toEqual([{ studentId: "y", status: "LATE", minutesLate: 3, expectedVersion: 5 }]);
    expect(mineInputs(conflicts).map((i) => i.studentId)).toEqual(["x", "y"]);
  });
});

describe("counts", () => {
  it("counts stored records and unmarked students", () => {
    const students = [student("a"), student("b", { record: record("ABSENT", 0) }), student("c", { record: record("LEFT_EARLY", 1) })];
    expect(countsOf(students)).toEqual({ students: 3, marked: 2, unmarked: 1, present: 0, absent: 1, late: 0, excused: 0, leftEarly: 1 });
  });

  it("summarizes what is shown: defaults and edits count, and the unsaved ones are reported", () => {
    const a = student("a");
    const b = student("b", { record: record("PRESENT", 0) });
    const c = student("c", { suggestedStatus: "EXCUSED" });
    const drafts = setStatus({}, b, "ABSENT");
    expect(summarize([a, b, c], drafts)).toEqual({ students: 3, present: 1, absent: 1, late: 0, excused: 1, leftEarly: 0, unsaved: 3 });
  });
});
