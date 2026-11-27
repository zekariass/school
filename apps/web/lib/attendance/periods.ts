import { ApiRequestError } from "@/lib/api-error";
import type { PeriodScope } from "./roll";

/**
 * Per-period rolls (M7d): which slot, subject and saved student group a roll is taken for. Pure helpers: the slots the
 * school enabled come from the API's answers (`slots`), never from a constant here.
 */

const PERIOD = /^P[1-8]$/;

/** True for P1..P8 (the only slots a subject may name). */
export const isPeriodSlot = (slot: string | null | undefined): boolean => !!slot && PERIOD.test(slot);

/** The period slots among the ones the school enabled, in the API's order. */
export const periodSlotsOf = (slots: readonly string[]): string[] => slots.filter(isPeriodSlot);

export interface SubjectLike {
  id: string;
  name: string;
  active: boolean;
}

export interface SubjectChoice {
  id: string;
  name: string;
}

/**
 * The subjects a roll of this class may name: the class's own subjects that are still active (the API refuses
 * anything else with `attendance.invalid_subject`), by name.
 */
export function subjectChoices(classSubjectIds: readonly string[] | undefined, schoolSubjects: readonly SubjectLike[] | undefined): SubjectChoice[] {
  const own = new Set(classSubjectIds ?? []);
  return (schoolSubjects ?? [])
    .filter((s) => s.active && own.has(s.id))
    .map((s) => ({ id: s.id, name: s.name }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

/** Active subjects of the school for the dashboard and report filters, by name. */
export function activeSubjects(schoolSubjects: readonly SubjectLike[] | undefined): SubjectChoice[] {
  return (schoolSubjects ?? [])
    .filter((s) => s.active)
    .map((s) => ({ id: s.id, name: s.name }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export interface GroupLike {
  id: string;
  name: string;
  studentIds: readonly string[];
}

export interface GroupChoice {
  id: string;
  name: string;
  size: number;
}

/** Saved groups of the class (an elective's roster), by name, empty groups left out (nobody to call). */
export function groupChoices(groups: readonly GroupLike[] | undefined): GroupChoice[] {
  return (groups ?? [])
    .filter((g) => g.studentIds.length > 0)
    .map((g) => ({ id: g.id, name: g.name, size: g.studentIds.length }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export interface RollLike {
  slot: string;
  sessionId?: string | null;
  subjectId?: string | null;
  groupId?: string | null;
}

/** The subject can be chosen only on a period roll nobody has taken yet: once the roll exists its subject never changes. */
export const subjectEditable = (roll: Pick<RollLike, "slot" | "sessionId">): boolean => isPeriodSlot(roll.slot) && !roll.sessionId;

/**
 * The subject a write names. Never on a slot that is not a period (the API answers 400
 * `attendance.subject_requires_period`); on an existing roll its own subject (naming another is a 409
 * `attendance.subject_mismatch`); on a new roll the choice, if it is one of the offered subjects.
 */
export function subjectForWrite(roll: RollLike, chosen: string | null, choices: readonly SubjectChoice[]): string | undefined {
  if (!isPeriodSlot(roll.slot)) return undefined;
  if (roll.sessionId) return roll.subjectId ?? undefined;
  return chosen && choices.some((c) => c.id === chosen) ? chosen : undefined;
}

/** The subject and group of a write, from the roll on screen (the roster of that roll decides the group). */
export function periodScope(roll: RollLike, chosenSubject: string | null, choices: readonly SubjectChoice[]): PeriodScope {
  const subjectId = subjectForWrite(roll, chosenSubject, choices);
  return { ...(subjectId ? { subjectId } : {}), ...(roll.groupId ? { groupId: roll.groupId } : {}) };
}

/**
 * The write's answer does not repeat the subject: a roll that was just created for one (`wasNew`) gets its id and name
 * from the choice that was sent, so the screen keeps showing it as fixed.
 */
export function withCreatedSubject<T extends object>(roll: T, wasNew: boolean, subjectId: string | undefined, choices: readonly SubjectChoice[]): T {
  const named = wasNew && subjectId ? choices.find((c) => c.id === subjectId) : undefined;
  return named ? { ...roll, subjectId: named.id, subjectName: named.name } : roll;
}

/** The refusals a period roll can meet, by the API's problem codes. */
export type PeriodRefusal = "invalidSubject" | "subjectRequiresPeriod" | "subjectMismatch" | "invalidGroup" | "studentNotInGroup";

const REFUSALS: Record<string, PeriodRefusal> = {
  "attendance.invalid_subject": "invalidSubject",
  "attendance.subject_requires_period": "subjectRequiresPeriod",
  "attendance.subject_mismatch": "subjectMismatch",
  "attendance.invalid_group": "invalidGroup",
  "attendance.student_not_in_group": "studentNotInGroup",
};

export function periodRefusal(error: unknown): PeriodRefusal | null {
  if (!(error instanceof ApiRequestError) || !error.problem) return null;
  return REFUSALS[error.problem.code] ?? null;
}

/** After these refusals what is on screen is stale (the roll has another subject, the group is gone): load it again. */
export const refusalNeedsReload = (refusal: PeriodRefusal | null): boolean =>
  refusal === "subjectMismatch" || refusal === "invalidGroup" || refusal === "studentNotInGroup";

/** The subject line of a roll or a dashboard row: the name the API answered, nothing when there is none. */
export const subjectLabel = (row: { subjectName?: string | null }): string | null => row.subjectName?.trim() || null;
