import type { components } from "@school/api-client";
import { MAX_NOTE, STATUSES, isStatus, takesMinutes, type AttendanceStatus } from "./status";

/**
 * The quick roll's pure model: what a student shows, which edits are pending, the request that saves them
 * (with the versions the API needs to detect conflicts) and what to do with the answer.
 *
 * Conflict policy of the API: the first write stays; a different mark from someone who had not seen the stored
 * record is a CONFLICT (per student, nothing overwritten). It is resolved by sending the mark again with
 * `expectedVersion` = the stored version ("use mine"), or by accepting the stored record ("keep theirs").
 */

type S = components["schemas"];
export type RollView = S["AttendanceRollView"];
export type RollStudent = S["AttendanceRollStudent"];
export type RecordView = S["AttendanceRecordView"];
export type MarkInput = S["AttendanceMarkInput"];
export type RollRequest = S["AttendanceRollRequest"];
export type WriteResult = S["AttendanceWriteResult"];
export type RecordOutcome = S["AttendanceRecordOutcome"];
export type SubmittedMark = S["AttendanceSubmittedMark"];

/** What the roll shows or sends for one student. */
export interface Mark {
  status: AttendanceStatus;
  /** Only with LATE. */
  minutesLate?: number;
  /** The teacher's short note (staff only); never stored outside this page's memory and the API. */
  note?: string;
}

/** Unsaved edits, by student id: only students the teacher changed. In memory only (children's data). */
export type Drafts = Readonly<Record<string, Mark>>;

/** A conflicted mark: what is stored now and what this teacher tried to write. */
export interface Conflict {
  studentId: string;
  stored: RecordView;
  mine: Mark;
}
export type Conflicts = Readonly<Record<string, Conflict>>;

/** Clean form of a mark: minutes only with LATE, a trimmed note without empty strings. */
export function normalizeMark(mark: Mark): Mark {
  const note = mark.note?.trim().slice(0, MAX_NOTE);
  return {
    status: mark.status,
    ...(takesMinutes(mark.status) && mark.minutesLate !== undefined ? { minutesLate: mark.minutesLate } : {}),
    ...(note ? { note } : {}),
  };
}

export function sameMark(a: Mark, b: Mark): boolean {
  const x = normalizeMark(a);
  const y = normalizeMark(b);
  return x.status === y.status && (x.minutesLate ?? null) === (y.minutesLate ?? null) && (x.note ?? "") === (y.note ?? "");
}

/** The mark of a stored record. */
export function markOfRecord(record: RecordView): Mark {
  return normalizeMark({
    status: record.status,
    ...(record.minutesLate != null ? { minutesLate: record.minutesLate } : {}),
    ...(record.note ? { note: record.note } : {}),
  });
}

/** What an unmarked student is taken to be: present, or excused when an acknowledged absence report covers the day. */
export function defaultMark(student: RollStudent): Mark {
  return { status: student.suggestedStatus && isStatus(student.suggestedStatus) ? student.suggestedStatus : "PRESENT" };
}

/** The saved state of a student (a stored record, or the default for an unmarked one): edits are measured against it. */
export function baselineMark(student: RollStudent): Mark {
  return student.record ? markOfRecord(student.record) : defaultMark(student);
}

/** What the roll shows: the unsaved edit, else the stored record, else the default. */
export function shownMark(student: RollStudent, drafts: Drafts): Mark {
  return drafts[student.studentId] ?? baselineMark(student);
}

/** Sets a student's mark; an edit that equals the saved state is no edit. */
export function setMark(drafts: Drafts, student: RollStudent, mark: Mark): Drafts {
  const next = { ...drafts };
  const clean = normalizeMark(mark);
  if (sameMark(clean, baselineMark(student))) delete next[student.studentId];
  else next[student.studentId] = clean;
  return next;
}

/** Changes only the status of the shown mark (a tap): minutes are dropped unless the new status is LATE. */
export function setStatus(drafts: Drafts, student: RollStudent, status: AttendanceStatus): Drafts {
  const shown = shownMark(student, drafts);
  return setMark(drafts, student, { status, ...(shown.note ? { note: shown.note } : {}), ...(takesMinutes(status) && shown.minutesLate ? { minutesLate: shown.minutesLate } : {}) });
}

export function setMinutes(drafts: Drafts, student: RollStudent, minutesLate: number | undefined): Drafts {
  const { status, note } = shownMark(student, drafts);
  return setMark(drafts, student, { status, ...(note ? { note } : {}), ...(minutesLate !== undefined ? { minutesLate } : {}) });
}

export function setNote(drafts: Drafts, student: RollStudent, note: string): Drafts {
  return setMark(drafts, student, { ...shownMark(student, drafts), note });
}

/** Students the roll has no record for yet (they show as present by default and are not saved). */
export const unsavedStudents = (roll: Pick<RollView, "students">): RollStudent[] => roll.students.filter((s) => !s.record);

export const pendingEditCount = (drafts: Drafts): number => Object.keys(drafts).length;

/** Everything a Save would write: the explicit edits, in roll order, each with the version it was edited from. */
export function draftInputs(roll: Pick<RollView, "students">, drafts: Drafts): MarkInput[] {
  const inputs: MarkInput[] = [];
  for (const student of roll.students) {
    const draft = drafts[student.studentId];
    if (!draft) continue;
    const mark = normalizeMark(draft);
    inputs.push({
      studentId: student.studentId,
      status: mark.status,
      ...(mark.minutesLate !== undefined ? { minutesLate: mark.minutesLate } : {}),
      ...(mark.note ? { note: mark.note } : {}),
      // A student with a record is edited from that record's version; one without has none to name.
      ...(student.record?.version !== undefined ? { expectedVersion: student.record.version } : {}),
    });
  }
  return inputs;
}

export interface WriteOptions {
  roll: Pick<RollView, "students" | "date" | "slot" | "sessionVersion" | "locked">;
  drafts: Drafts;
  /** Also mark every student without a record present (excused where the API knows an acknowledged report). */
  markAllPresent?: boolean;
  /** Required by the API for a locked roll; ignored otherwise. */
  overrideReason?: string;
  /** Only these students' edits (used to resend single resolved conflicts). */
  onlyStudentIds?: ReadonlySet<string>;
  /** The period roll's subject and the group the roster was restricted to (see `periodScope`). */
  scope?: PeriodScope;
}

/** What makes a roll a period roll: the subject (P1..P8 only) and the saved group of an elective. */
export interface PeriodScope {
  subjectId?: string;
  groupId?: string;
}

const scopeFields = (scope: PeriodScope | undefined): PeriodScope => ({
  ...(scope?.subjectId ? { subjectId: scope.subjectId } : {}),
  ...(scope?.groupId ? { groupId: scope.groupId } : {}),
});

/** The request that saves the pending edits of a roll. `baseVersion` is the roll version the teacher read. */
export function buildWrite(options: WriteOptions): RollRequest {
  const { roll, drafts, markAllPresent, overrideReason, onlyStudentIds, scope } = options;
  const scoped = onlyStudentIds ? Object.fromEntries(Object.entries(drafts).filter(([id]) => onlyStudentIds.has(id))) : drafts;
  const reason = overrideReason?.trim();
  return {
    date: roll.date,
    slot: roll.slot,
    ...scopeFields(scope),
    ...(roll.sessionVersion != null ? { baseVersion: roll.sessionVersion } : {}),
    ...(markAllPresent ? { markAllPresent: true } : {}),
    ...(roll.locked && reason ? { overrideReason: reason.slice(0, 200) } : {}),
    records: draftInputs(roll, scoped),
  };
}

/** True when saving would send nothing the API accepts (no edits and no mark-all): it answers 409 on a new roll. */
export const isEmptyWrite = (request: Pick<RollRequest, "records" | "markAllPresent">): boolean =>
  !request.markAllPresent && (request.records?.length ?? 0) === 0;

/** The 409 of a roll with conflicting marks carries the same body as a 200; a problem (`code`, `status`) does not. */
export function isWriteResult(value: unknown): value is WriteResult {
  return typeof value === "object" && value !== null && Array.isArray((value as { outcomes?: unknown }).outcomes);
}

export function conflictsOf(result: Pick<WriteResult, "outcomes">): Conflict[] {
  const out: Conflict[] = [];
  for (const o of result.outcomes) {
    if (o.outcome !== "CONFLICT" || !o.submitted || !isStatus(o.submitted.status)) continue;
    out.push({
      studentId: o.studentId,
      stored: o.record,
      mine: normalizeMark({
        status: o.submitted.status,
        ...(o.submitted.minutesLate != null ? { minutesLate: o.submitted.minutesLate } : {}),
        ...(o.submitted.note ? { note: o.submitted.note } : {}),
      }),
    });
  }
  return out;
}

export interface Applied {
  roll: RollView;
  drafts: Drafts;
  conflicts: Conflicts;
}

/**
 * Folds the API's answer into the roll on screen: written and unchanged marks become the stored records (their
 * edits are done), conflicted ones keep the stored record and move to the conflict list (their edit is dropped:
 * "use mine" will resend it with the stored version). The roll version moves on to the answer's.
 */
export function applyResult(roll: RollView, drafts: Drafts, conflicts: Conflicts, result: WriteResult): Applied {
  const records = new Map(result.outcomes.map((o) => [o.studentId, o]));
  const nextDrafts: Record<string, Mark> = { ...drafts };
  const nextConflicts: Record<string, Conflict> = { ...conflicts };
  for (const o of result.outcomes) {
    delete nextDrafts[o.studentId];
    delete nextConflicts[o.studentId];
  }
  for (const c of conflictsOf(result)) nextConflicts[c.studentId] = c;
  const students = roll.students.map((s) => {
    const o = records.get(s.studentId);
    return o ? { ...s, record: o.record } : s;
  });
  return {
    roll: {
      ...roll,
      students,
      sessionId: result.sessionId,
      ...(result.sessionVersion != null ? { sessionVersion: result.sessionVersion } : {}),
      counts: countsOf(students),
    },
    drafts: nextDrafts,
    conflicts: nextConflicts,
  };
}

/** Accepts what is stored for a student: the conflict and the student's own edit are dropped. */
export function keepTheirs(drafts: Drafts, conflicts: Conflicts, studentId: string): { drafts: Drafts; conflicts: Conflicts } {
  const nextDrafts = { ...drafts };
  const nextConflicts = { ...conflicts };
  delete nextDrafts[studentId];
  delete nextConflicts[studentId];
  return { drafts: nextDrafts, conflicts: nextConflicts };
}

/** The marks to resend for "use mine": each with `expectedVersion` = the version now stored. */
export function mineInputs(conflicts: Conflicts, studentIds?: readonly string[]): MarkInput[] {
  return Object.values(conflicts)
    .filter((c) => !studentIds || studentIds.includes(c.studentId))
    .map((c) => ({
      studentId: c.studentId,
      status: c.mine.status,
      ...(c.mine.minutesLate !== undefined ? { minutesLate: c.mine.minutesLate } : {}),
      ...(c.mine.note ? { note: c.mine.note } : {}),
      ...(c.stored.version !== undefined ? { expectedVersion: c.stored.version } : {}),
    }));
}

/** The request for "use mine": the chosen marks only, against the roll's current version. */
export function buildResolve(
  roll: Pick<RollView, "date" | "slot" | "sessionVersion" | "locked">,
  conflicts: Conflicts,
  studentIds: readonly string[],
  overrideReason?: string,
  scope?: PeriodScope,
): RollRequest {
  const reason = overrideReason?.trim();
  return {
    date: roll.date,
    slot: roll.slot,
    ...scopeFields(scope),
    ...(roll.sessionVersion != null ? { baseVersion: roll.sessionVersion } : {}),
    ...(roll.locked && reason ? { overrideReason: reason.slice(0, 200) } : {}),
    records: mineInputs(conflicts, studentIds),
  };
}

export interface RollCounts {
  students: number;
  present: number;
  absent: number;
  late: number;
  excused: number;
  leftEarly: number;
  /** Students without a stored record. */
  unsaved: number;
}

const KEY: Record<AttendanceStatus, keyof RollCounts> = {
  PRESENT: "present",
  ABSENT: "absent",
  LATE: "late",
  EXCUSED: "excused",
  LEFT_EARLY: "leftEarly",
};

/** The API's count shape from the students' stored records (kept in step after a write). */
export function countsOf(students: readonly RollStudent[]): S["AttendanceCounts"] {
  const out = { students: students.length, marked: 0, unmarked: 0, present: 0, absent: 0, late: 0, excused: 0, leftEarly: 0 };
  for (const s of students) {
    if (!s.record) {
      out.unmarked++;
      continue;
    }
    out.marked++;
    out[KEY[s.record.status] as "present"]++;
  }
  return out;
}

/** What the summary bar shows: the shown marks (edits and defaults included) and how many are not saved yet. */
export function summarize(students: readonly RollStudent[], drafts: Drafts): RollCounts {
  const out: RollCounts = { students: students.length, present: 0, absent: 0, late: 0, excused: 0, leftEarly: 0, unsaved: 0 };
  for (const s of students) {
    out[KEY[shownMark(s, drafts).status] as "present"]++;
    if (!s.record || drafts[s.studentId]) out.unsaved++;
  }
  return out;
}

/** True when the student's shown mark was not written yet (a default or an edit). */
export const isUnsaved = (student: RollStudent, drafts: Drafts): boolean => !student.record || !!drafts[student.studentId];

/** The order of the statuses in pickers and summaries. */
export const STATUS_ORDER = STATUSES;
