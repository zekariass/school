import { describe, expect, it } from "vitest";
import { classPointsAbilities, pointsPageAccess, showBadges, showLeaderboard } from "./access";

const none = new Set<string>();
const active = { status: "ACTIVE" as const };

describe("classPointsAbilities", () => {
  it("gives the lead teacher everything for their class", () => {
    expect(classPointsAbilities({ ...active, myMembership: "TEACHER", myTeacherRole: "LEAD" }, none)).toEqual({
      canUse: true,
      canAward: true,
      canManageSkills: true,
      canAwardBadges: true,
      canShareSchoolWide: false,
    });
  });

  it("lets a subject teacher award but not manage skills", () => {
    const a = classPointsAbilities({ ...active, myMembership: "TEACHER", myTeacherRole: "SUBJECT_TEACHER" }, none);
    expect(a.canAward).toBe(true);
    expect(a.canManageSkills).toBe(false);
  });

  it("lets a co-teacher manage skills", () => {
    expect(classPointsAbilities({ ...active, myMembership: "TEACHER", myTeacherRole: "CO_TEACHER" }, none).canManageSkills).toBe(true);
  });

  it("gives students and bystanders nothing", () => {
    expect(classPointsAbilities({ ...active, myMembership: "STUDENT" }, none).canUse).toBe(false);
    expect(classPointsAbilities(active, none).canUse).toBe(false);
  });

  it("keeps an archived class read-only", () => {
    const a = classPointsAbilities({ status: "ARCHIVED", myMembership: "TEACHER", myTeacherRole: "LEAD" }, none);
    expect(a.canUse).toBe(true);
    expect(a.canAward).toBe(false);
  });

  it("gives class managers and points managers access without teaching", () => {
    expect(classPointsAbilities(active, new Set(["class.manage"])).canManageSkills).toBe(true);
    const pm = classPointsAbilities(active, new Set(["school.points.manage"]));
    expect(pm.canUse).toBe(true);
    expect(pm.canShareSchoolWide).toBe(true);
  });
});

describe("pointsPageAccess", () => {
  it("shows a teacher the report of their classes but not the school-wide one", () => {
    expect(pointsPageAccess(none, true)).toEqual({ canSeeReport: true, canSeeSchoolReport: false, canManage: false });
  });

  it("lets readers see the school report and managers manage", () => {
    expect(pointsPageAccess(new Set(["school.points.read"]), false)).toEqual({
      canSeeReport: true,
      canSeeSchoolReport: true,
      canManage: false,
    });
    expect(pointsPageAccess(new Set(["school.points.manage"]), false).canManage).toBe(true);
  });

  it("shows nothing to everybody else", () => {
    expect(pointsPageAccess(new Set(["school.read"]), false).canSeeReport).toBe(false);
  });
});

describe("setting gates", () => {
  it("hides the leaderboard and badges unless the school enabled them", () => {
    expect(showLeaderboard(undefined)).toBe(false);
    expect(showLeaderboard({ leaderboardEnabled: false })).toBe(false);
    expect(showLeaderboard({ leaderboardEnabled: true })).toBe(true);
    expect(showBadges({})).toBe(false);
    expect(showBadges({ badgesEnabled: true })).toBe(true);
  });
});
