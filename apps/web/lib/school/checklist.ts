/** The setup steps of a new school, from the counts of `GET /schools/{id}/summary`. */

export type ChecklistId = "year" | "grades" | "subjects" | "teacher" | "class" | "students";

export interface ChecklistCounts {
  hasCurrentYear: boolean;
  gradeLevels: number;
  subjects: number;
  teachers: number;
  classes: number;
  students: number;
}

export interface ChecklistItem {
  id: ChecklistId;
  done: boolean;
}

/** In the order a school is usually set up. */
export function buildChecklist(counts: ChecklistCounts): ChecklistItem[] {
  return [
    { id: "year", done: counts.hasCurrentYear },
    { id: "grades", done: counts.gradeLevels > 0 },
    { id: "subjects", done: counts.subjects > 0 },
    { id: "teacher", done: counts.teachers > 0 },
    { id: "class", done: counts.classes > 0 },
    { id: "students", done: counts.students > 0 },
  ];
}
