import { describe, expect, it } from "vitest";
import { classAbilities, toClassCard, type ClassLike } from "./class-view";

const base: ClassLike = {
  id: "c1",
  name: "8A",
  studentCount: 30,
  teacherCount: 2,
  status: "ACTIVE",
};

describe("toClassCard", () => {
  it("fills in a default icon and color", () => {
    const card = toClassCard(base);
    expect(card.icon).toBe("📚");
    expect(card.color).toBe("#2F80ED");
    expect(card.grade).toBeNull();
    expect(card.archived).toBe(false);
  });

  it("keeps the class's own icon and valid color, drops an invalid color", () => {
    expect(toClassCard({ ...base, icon: "🎨", color: "#112233" })).toMatchObject({ icon: "🎨", color: "#112233" });
    expect(toClassCard({ ...base, color: "red" }).color).toBe("#2F80ED");
  });

  it("shows the grade and flags archived classes", () => {
    const card = toClassCard({ ...base, gradeLevelName: " Grade 8 ", status: "ARCHIVED" });
    expect(card.grade).toBe("Grade 8");
    expect(card.archived).toBe(true);
  });

  it("reports the teacher role only for teachers of the class", () => {
    expect(toClassCard({ ...base, myMembership: "TEACHER", myTeacherRole: "LEAD" }).myTeacherRole).toBe("LEAD");
    expect(toClassCard({ ...base, myMembership: "STUDENT", myTeacherRole: "LEAD" }).myTeacherRole).toBeNull();
  });
});

describe("classAbilities", () => {
  const none = new Set<string>();
  const manager = new Set(["class.manage"]);

  it("lets class administrators do everything", () => {
    expect(classAbilities(base, manager)).toEqual({ canTeach: true, canLead: true, canManage: true, rosterOpen: true });
  });

  it("limits a co-teacher to the roster, groups and codes", () => {
    const abilities = classAbilities({ ...base, myMembership: "TEACHER", myTeacherRole: "CO_TEACHER" }, none);
    expect(abilities).toMatchObject({ canTeach: true, canLead: false, canManage: false });
  });

  it("lets the lead teacher rename and manage teachers but not move the class", () => {
    const abilities = classAbilities({ ...base, myMembership: "TEACHER", myTeacherRole: "LEAD" }, none);
    expect(abilities).toMatchObject({ canTeach: true, canLead: true, canManage: false });
  });

  it("gives nothing to someone who only sees the class, and freezes archived rosters", () => {
    expect(classAbilities({ ...base, status: "ARCHIVED" }, none)).toEqual({
      canTeach: false,
      canLead: false,
      canManage: false,
      rosterOpen: false,
    });
  });
});
