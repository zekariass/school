import { describe, expect, it } from "vitest";
import { canRemoveTeacher, type TeacherRow } from "./class-teachers";

const lead: TeacherRow = { id: "1", teacherId: "u1", role: "LEAD" };
const co: TeacherRow = { id: "2", teacherId: "u2", role: "CO_TEACHER" };
const sub: TeacherRow = { id: "3", teacherId: "u3", role: "SUBJECT_TEACHER" };
const all = [lead, co, sub];

describe("canRemoveTeacher", () => {
  it("never offers removing the last lead", () => {
    expect(canRemoveTeacher(lead, all, { canLead: true, userId: "x" })).toBe(false);
    expect(canRemoveTeacher(lead, all, { canLead: true, userId: "u1" })).toBe(false);
  });

  it("allows removing a lead when another lead remains", () => {
    const two = [lead, { id: "4", teacherId: "u4", role: "LEAD" as const }, co];
    expect(canRemoveTeacher(lead, two, { canLead: true, userId: "x" })).toBe(true);
  });

  it("lets leads and administrators remove other teachers", () => {
    expect(canRemoveTeacher(co, all, { canLead: true, userId: "u1" })).toBe(true);
  });

  it("lets other teachers only leave themselves", () => {
    expect(canRemoveTeacher(co, all, { canLead: false, userId: "u2" })).toBe(true);
    expect(canRemoveTeacher(sub, all, { canLead: false, userId: "u2" })).toBe(false);
  });
});
