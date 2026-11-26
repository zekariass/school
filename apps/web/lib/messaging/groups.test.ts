import { describe, expect, it } from "vitest";
import { followsRoster, groupFormProblem, groupRequest, isClassBound, needsTitle, pendingApprovals } from "./groups";

describe("group creation form", () => {
  it("needs a kind first, then a class for class kinds", () => {
    expect(groupFormProblem({ kind: "", classId: "", title: "", memberIds: [] })).toBe("kind");
    expect(groupFormProblem({ kind: "CLASS_STUDENTS", classId: "", title: "", memberIds: [] })).toBe("class");
    expect(groupFormProblem({ kind: "CLASS_STUDENTS", classId: "c1", title: "", memberIds: [] })).toBeNull();
    expect(groupFormProblem({ kind: "CLASS_PARENTS", classId: "c1", title: "", memberIds: [] })).toBeNull();
  });

  it("shows the rule: a group with students needs at least two students", () => {
    const base = { kind: "STUDENT_GROUP", classId: "c1", title: "Maths club" } as const;
    expect(groupFormProblem({ ...base, memberIds: [] })).toBe("tooFewStudents");
    expect(groupFormProblem({ ...base, memberIds: ["s1"] })).toBe("tooFewStudents");
    expect(groupFormProblem({ ...base, memberIds: ["s1", "s2"] })).toBeNull();
  });

  it("needs a title for staff and student groups and members for staff groups", () => {
    expect(groupFormProblem({ kind: "STAFF", classId: "", title: "  ", memberIds: ["u1"] })).toBe("title");
    expect(groupFormProblem({ kind: "STAFF", classId: "", title: "Grade 3 team", memberIds: [] })).toBe("members");
    expect(groupFormProblem({ kind: "STAFF", classId: "", title: "Grade 3 team", memberIds: ["u1"] })).toBeNull();
    expect(groupFormProblem({ kind: "SCHOOL_STAFF", classId: "", title: "Staff room", memberIds: ["u1"] })).toBeNull();
  });

  it("sends members only for kinds that have chosen members and a class only for class kinds", () => {
    expect(groupRequest({ kind: "CLASS_STUDENTS", classId: "c1", title: "", memberIds: ["x"], postingPolicy: "" })).toEqual({
      kind: "CLASS_STUDENTS",
      classId: "c1",
      title: undefined,
      memberUserIds: undefined,
      postingPolicy: undefined,
    });
    expect(groupRequest({ kind: "STAFF", classId: "c1", title: " Team ", memberIds: ["u1"], postingPolicy: "MODERATORS_ONLY" })).toEqual({
      kind: "STAFF",
      classId: undefined,
      title: "Team",
      memberUserIds: ["u1"],
      postingPolicy: "MODERATORS_ONLY",
    });
  });

  it("classifies the kinds", () => {
    expect(isClassBound("STUDENT_GROUP")).toBe(true);
    expect(isClassBound("STAFF")).toBe(false);
    expect(followsRoster("CLASS_PARENTS")).toBe(true);
    expect(needsTitle("CLASS_STUDENTS")).toBe(false);
  });

  it("finds the proposals waiting for approval", () => {
    expect(pendingApprovals([{ status: "ACTIVE" }, { status: "PENDING_APPROVAL" }])).toEqual([{ status: "PENDING_APPROVAL" }]);
  });
});
