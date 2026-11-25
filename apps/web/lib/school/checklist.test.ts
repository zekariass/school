import { describe, expect, it } from "vitest";
import { buildChecklist } from "./checklist";

describe("buildChecklist", () => {
  it("starts with everything open for a new school", () => {
    const list = buildChecklist({ hasCurrentYear: false, gradeLevels: 0, subjects: 0, teachers: 0, classes: 0, students: 0 });
    expect(list.map((i) => i.id)).toEqual(["year", "grades", "subjects", "teacher", "class", "students"]);
    expect(list.every((i) => !i.done)).toBe(true);
  });

  it("ticks what exists", () => {
    const list = buildChecklist({ hasCurrentYear: true, gradeLevels: 3, subjects: 0, teachers: 1, classes: 0, students: 12 });
    expect(Object.fromEntries(list.map((i) => [i.id, i.done]))).toEqual({
      year: true,
      grades: true,
      subjects: false,
      teacher: true,
      class: false,
      students: true,
    });
  });
});
