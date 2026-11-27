import { describe, expect, it } from "vitest";
import { ApiRequestError } from "@/lib/api-error";
import {
  activeSubjects,
  groupChoices,
  isPeriodSlot,
  periodRefusal,
  periodScope,
  periodSlotsOf,
  refusalNeedsReload,
  subjectChoices,
  subjectEditable,
  subjectForWrite,
  subjectLabel,
  withCreatedSubject,
} from "./periods";
import { ak } from "./queries";
import { buildResolve, buildWrite, type RollView } from "./roll";

const math = { id: "m", name: "Mathematics", active: true };
const art = { id: "a", name: "Art", active: true };
const old = { id: "o", name: "Latin", active: false };
const other = { id: "x", name: "Physics", active: true };

const problem = (code: string, status = 400) =>
  new ApiRequestError(status, { type: "about:blank", title: "t", status, code } as never);

describe("slots", () => {
  it("knows the period slots P1..P8 only", () => {
    for (const s of ["P1", "P4", "P8"]) expect(isPeriodSlot(s)).toBe(true);
    for (const s of ["DAY", "AM", "PM", "P0", "P9", "p1", "", null, undefined]) expect(isPeriodSlot(s)).toBe(false);
  });

  it("offers only the period slots the school enabled, in the API's order", () => {
    expect(periodSlotsOf(["DAY", "P1", "P2", "AM"])).toEqual(["P1", "P2"]);
    expect(periodSlotsOf(["DAY"])).toEqual([]);
  });
});

describe("subject and group choices", () => {
  it("offers the class's own active subjects by name; retired and foreign subjects are never offered", () => {
    expect(subjectChoices(["m", "a", "o"], [math, art, old, other])).toEqual([
      { id: "a", name: "Art" },
      { id: "m", name: "Mathematics" },
    ]);
    expect(subjectChoices(undefined, [math])).toEqual([]);
    expect(subjectChoices(["m"], undefined)).toEqual([]);
  });

  it("lists the school's active subjects for the dashboard filter", () => {
    expect(activeSubjects([math, old, art]).map((s) => s.id)).toEqual(["a", "m"]);
  });

  it("offers saved groups by name with their size and leaves empty groups out", () => {
    expect(
      groupChoices([
        { id: "g2", name: "French", studentIds: ["s1", "s2"] },
        { id: "g0", name: "Empty", studentIds: [] },
        { id: "g1", name: "Chess", studentIds: ["s3"] },
      ]),
    ).toEqual([
      { id: "g1", name: "Chess", size: 1 },
      { id: "g2", name: "French", size: 2 },
    ]);
  });

  it("names the subject of a row only when the API did", () => {
    expect(subjectLabel({ subjectName: "Art" })).toBe("Art");
    expect(subjectLabel({ subjectName: "  " })).toBeNull();
    expect(subjectLabel({})).toBeNull();
  });
});

describe("the subject of a write", () => {
  const choices = subjectChoices(["m", "a"], [math, art]);

  it("is never named on a slot that is not a period", () => {
    expect(subjectForWrite({ slot: "DAY" }, "m", choices)).toBeUndefined();
    expect(subjectForWrite({ slot: "AM", sessionId: "s", subjectId: "m" }, "m", choices)).toBeUndefined();
  });

  it("is the choice on a new period roll, if it is one that is offered", () => {
    expect(subjectForWrite({ slot: "P2" }, "m", choices)).toBe("m");
    expect(subjectForWrite({ slot: "P2" }, "x", choices)).toBeUndefined();
    expect(subjectForWrite({ slot: "P2" }, null, choices)).toBeUndefined();
  });

  it("is the roll's own subject once the roll exists, whatever was chosen", () => {
    expect(subjectForWrite({ slot: "P2", sessionId: "s", subjectId: "a" }, "m", choices)).toBe("a");
    expect(subjectForWrite({ slot: "P2", sessionId: "s" }, "m", choices)).toBeUndefined();
  });

  it("can be chosen only on a period roll nobody has taken", () => {
    expect(subjectEditable({ slot: "P1" })).toBe(true);
    expect(subjectEditable({ slot: "P1", sessionId: "s" })).toBe(false);
    expect(subjectEditable({ slot: "DAY" })).toBe(false);
  });

  it("keeps the group of the roll on screen in the scope", () => {
    expect(periodScope({ slot: "P3", groupId: "g1" }, "m", choices)).toEqual({ subjectId: "m", groupId: "g1" });
    expect(periodScope({ slot: "DAY" }, "m", choices)).toEqual({});
  });

  it("names the subject of a roll that was just created", () => {
    const roll = { sessionId: "s" };
    expect(withCreatedSubject(roll, true, "m", choices)).toEqual({ sessionId: "s", subjectId: "m", subjectName: "Mathematics" });
    expect(withCreatedSubject(roll, false, "m", choices)).toBe(roll);
    expect(withCreatedSubject(roll, true, undefined, choices)).toBe(roll);
    expect(withCreatedSubject(roll, true, "zzz", choices)).toBe(roll);
  });
});

describe("the request of a period roll", () => {
  const roll = {
    date: "2026-11-25",
    slot: "P2",
    sessionVersion: 3,
    locked: false,
    students: [{ studentId: "s1", displayName: "A", record: { version: 1 } }],
  } as unknown as Pick<RollView, "students" | "date" | "slot" | "sessionVersion" | "locked">;

  it("carries the slot, subject and group next to baseVersion and each record's expectedVersion", () => {
    const request = buildWrite({
      roll,
      drafts: { s1: { status: "ABSENT" } },
      scope: { subjectId: "m", groupId: "g1" },
    });
    expect(request).toEqual({
      date: "2026-11-25",
      slot: "P2",
      subjectId: "m",
      groupId: "g1",
      baseVersion: 3,
      records: [{ studentId: "s1", status: "ABSENT", expectedVersion: 1 }],
    });
  });

  it("leaves subject and group out of a whole-day roll", () => {
    const request = buildWrite({ roll: { ...roll, slot: "DAY" }, drafts: {}, markAllPresent: true });
    expect(request).not.toHaveProperty("subjectId");
    expect(request).not.toHaveProperty("groupId");
    expect(request.slot).toBe("DAY");
  });

  it("carries the scope into 'use mine' too", () => {
    const stored = { version: 4, status: "ABSENT" } as never;
    const request = buildResolve(roll, { s1: { studentId: "s1", stored, mine: { status: "PRESENT" } } }, ["s1"], undefined, { subjectId: "m" });
    expect(request.subjectId).toBe("m");
    expect(request.slot).toBe("P2");
    expect(request.records).toEqual([{ studentId: "s1", status: "PRESENT", expectedVersion: 4 }]);
  });
});

describe("one roll per slot", () => {
  it("keys the roll of each slot and group apart, so switching never reuses or overwrites another's", () => {
    const keys = [
      ak.roll("sch", "c1", "2026-11-25", "P1"),
      ak.roll("sch", "c1", "2026-11-25", "P2"),
      ak.roll("sch", "c1", "2026-11-25", "P2", "g1"),
      ak.roll("sch", "c1", "2026-11-25", null),
    ].map((k) => JSON.stringify(k));
    expect(new Set(keys).size).toBe(4);
    // All of the class's rolls still share the prefix one invalidation uses.
    for (const key of [ak.roll("sch", "c1", "2026-11-25", "P1"), ak.roll("sch", "c1", null, null, "g1")]) {
      expect(key.slice(0, ak.allRolls("sch", "c1").length)).toEqual([...ak.allRolls("sch", "c1")]);
    }
  });

  it("keys the dashboard of a subject apart", () => {
    expect(JSON.stringify(ak.dashboard("s", null, "P1", "m"))).not.toBe(JSON.stringify(ak.dashboard("s", null, "P1")));
  });
});

describe("refusals", () => {
  it("recognises the period refusals by the API's code", () => {
    expect(periodRefusal(problem("attendance.invalid_subject"))).toBe("invalidSubject");
    expect(periodRefusal(problem("attendance.subject_requires_period"))).toBe("subjectRequiresPeriod");
    expect(periodRefusal(problem("attendance.subject_mismatch", 409))).toBe("subjectMismatch");
    expect(periodRefusal(problem("attendance.invalid_group"))).toBe("invalidGroup");
    expect(periodRefusal(problem("attendance.student_not_in_group", 409))).toBe("studentNotInGroup");
    expect(periodRefusal(problem("attendance.locked", 409))).toBeNull();
    expect(periodRefusal(new Error("x"))).toBeNull();
    expect(periodRefusal(new ApiRequestError(500))).toBeNull();
  });

  it("asks for a reload when what is on screen is stale", () => {
    expect(refusalNeedsReload("subjectMismatch")).toBe(true);
    expect(refusalNeedsReload("invalidGroup")).toBe(true);
    expect(refusalNeedsReload("studentNotInGroup")).toBe(true);
    expect(refusalNeedsReload("invalidSubject")).toBe(false);
    expect(refusalNeedsReload(null)).toBe(false);
  });
});
