export interface TeacherRow {
  id: string;
  teacherId: string;
  role: "LEAD" | "CO_TEACHER" | "SUBJECT_TEACHER" | "ASSISTANT";
}

/**
 * Whether to offer "remove" on a teacher row. Leads and class administrators may remove
 * anyone except the last lead (the API answers 409 `class.last_lead`); any other teacher
 * may only leave the class themselves.
 */
export function canRemoveTeacher(
  row: TeacherRow,
  all: readonly TeacherRow[],
  viewer: { canLead: boolean; userId: string },
): boolean {
  const leads = all.filter((r) => r.role === "LEAD");
  if (row.role === "LEAD" && leads.length <= 1) return false;
  if (viewer.canLead) return true;
  return row.teacherId === viewer.userId;
}
