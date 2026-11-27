import type { components } from '@school/api-client';

import { isPeriodSlot, parseRollState } from '@/lib/sync/operations';
import {
  isAttendanceOp,
  isAttendanceStatus,
  type AttendanceMark,
  type AttendanceStatus,
  type NewOperation,
  type QueuedOperation,
} from '@/lib/sync/types';

/**
 * The roll of a class on a date, as the app keeps it (in memory and in the offline cache). What it holds about a
 * child: id, display name, avatar media id (never a signed link), the mark (status, minutes late, version) and,
 * from an acknowledged absence report, only the reason CODE. The teacher's note on a mark and the absence note
 * are never part of it: they are free text about a child, shown from a live response only.
 */

export interface RollRecord {
  readonly status: AttendanceStatus;
  readonly minutesLate: number | null;
  readonly version: number | null;
  readonly recordedBy: string | null;
}

export interface RollAbsence {
  readonly reportId: string;
  /** SUBMITTED or ACKNOWLEDGED. */
  readonly status: string;
  /** ILLNESS, FAMILY, APPOINTMENT or OTHER (a code, not the parent's words). */
  readonly reasonCode: string | null;
  readonly fromDate: string;
  readonly toDate: string;
}

export interface RollStudent {
  readonly studentId: string;
  readonly name: string;
  readonly avatarMediaId: string | null;
  /** `#rrggbb` of the avatar placeholder, when the student chose one. */
  readonly avatarColor: string | null;
  readonly record: RollRecord | null;
  /** EXCUSED when an acknowledged absence report covers the date and nobody marked the student yet. */
  readonly suggestedStatus: AttendanceStatus | null;
  readonly absence: RollAbsence | null;
}

export interface RollSnapshot {
  readonly schoolId: string;
  readonly classId: string;
  /** School-local date. */
  readonly date: string;
  readonly slot: string;
  readonly slots: readonly string[];
  readonly timeZone: string;
  /** The roll's version: the `baseVersion` of edits (null while nobody has taken the roll, or none is known). */
  readonly sessionVersion: number | null;
  readonly locked: boolean;
  readonly locksAt: string | null;
  readonly mayEditLocked: boolean;
  readonly students: readonly RollStudent[];
  /** Built from the class list, not from the server: no marks, no hints, no version. */
  readonly synthetic: boolean;
  /** The subject this roll was created for (period rolls only; null: none, or not known offline). */
  readonly subjectId: string | null;
  readonly subjectName: string | null;
  /** The saved group the roster was restricted to (null: the whole class). Never part of the cached roll. */
  readonly groupId: string | null;
}

type ApiRoll = components['schemas']['AttendanceRollView'];

const COLOR = /^#[0-9a-fA-F]{6}$/;

function readRecord(raw: components['schemas']['AttendanceRecordView'] | undefined): RollRecord | null {
  if (!raw || !isAttendanceStatus(raw.status)) return null;
  return {
    status: raw.status,
    minutesLate: typeof raw.minutesLate === 'number' ? raw.minutesLate : null,
    version: typeof raw.version === 'number' ? raw.version : null,
    recordedBy: raw.recordedBy ?? null,
  };
}

/** The API's roll, reduced to what the app keeps. */
export function rollFromApi(view: ApiRoll, schoolId: string): RollSnapshot {
  return {
    schoolId,
    classId: view.classId,
    date: view.date,
    slot: view.slot,
    slots: view.slots ?? [view.slot],
    timeZone: view.timeZone,
    sessionVersion: typeof view.sessionVersion === 'number' ? view.sessionVersion : null,
    locked: view.locked === true,
    locksAt: view.locksAt ?? null,
    mayEditLocked: view.mayEditLocked === true,
    synthetic: false,
    subjectId: view.subjectId ?? null,
    subjectName: view.subjectName ?? null,
    groupId: view.groupId ?? null,
    students: view.students.map((student) => {
      const color = student.avatarConfig?.color;
      return {
        studentId: student.studentId,
        name: student.displayName,
        avatarMediaId: student.avatar?.id ?? null,
        avatarColor: typeof color === 'string' && COLOR.test(color) ? color : null,
        record: readRecord(student.record),
        suggestedStatus: student.suggestedStatus && isAttendanceStatus(student.suggestedStatus) ? student.suggestedStatus : null,
        absence: student.absence
          ? {
              reportId: student.absence.reportId,
              status: student.absence.status,
              reasonCode: student.absence.reasonCode ?? null,
              fromDate: student.absence.fromDate,
              toDate: student.absence.toDate,
            }
          : null,
      };
    }),
  };
}

/** A roll for a date nobody has loaded: the class list, nobody marked. Only ever for the school's today. */
export function syntheticRoll(input: {
  schoolId: string;
  classId: string;
  date: string;
  slot: string;
  slots: readonly string[];
  timeZone: string;
  roster: readonly { id: string; name: string; avatarMediaId: string | null; avatarColor: string | null }[];
}): RollSnapshot {
  return {
    schoolId: input.schoolId,
    classId: input.classId,
    date: input.date,
    slot: input.slot,
    slots: input.slots,
    timeZone: input.timeZone,
    sessionVersion: null,
    locked: false,
    locksAt: null,
    mayEditLocked: false,
    synthetic: true,
    subjectId: null,
    subjectName: null,
    groupId: null,
    students: input.roster.map((student) => ({
      studentId: student.id,
      name: student.name,
      avatarMediaId: student.avatarMediaId,
      avatarColor: student.avatarColor,
      record: null,
      suggestedStatus: null,
      absence: null,
    })),
  };
}

// --- the screen's view: the roll plus what was edited and is still waiting -------------------------------------

export interface EffectiveStudent extends RollStudent {
  /** What the teacher sees: the stored mark with the waiting edits on top (null: nobody marked the student). */
  readonly status: AttendanceStatus | null;
  readonly minutesLate: number | null;
  /** An edit of this student is waiting to be sent. */
  readonly queued: boolean;
}

export const sameRoll = (op: QueuedOperation, roll: Pick<RollSnapshot, 'classId' | 'date' | 'slot'>): boolean =>
  isAttendanceOp(op) && op.classId === roll.classId && op.payload.date === roll.date && op.payload.slot === roll.slot;

/** The roll with the pending operations (oldest first) applied, so a tap shows at once and survives a restart. */
export function effectiveStudents(roll: RollSnapshot, operations: readonly QueuedOperation[]): EffectiveStudent[] {
  const edits = new Map<string, AttendanceMark>();
  for (const op of operations) {
    if (op.status !== 'PENDING' || !isAttendanceOp(op) || !sameRoll(op, roll)) continue;
    for (const record of op.payload.records) edits.set(record.studentId, record);
  }
  return roll.students.map((student) => {
    const edit = edits.get(student.studentId);
    if (edit) return { ...student, status: edit.status, minutesLate: edit.minutesLate ?? null, queued: true };
    return { ...student, status: student.record?.status ?? null, minutesLate: student.record?.minutesLate ?? null, queued: false };
  });
}

export interface RollCounts {
  readonly present: number;
  readonly absent: number;
  readonly late: number;
  readonly excused: number;
  readonly leftEarly: number;
  readonly unmarked: number;
  readonly total: number;
}

export function countStatuses(students: readonly Pick<EffectiveStudent, 'status'>[]): RollCounts {
  const counts = { present: 0, absent: 0, late: 0, excused: 0, leftEarly: 0, unmarked: 0, total: students.length };
  for (const student of students) {
    switch (student.status) {
      case 'PRESENT':
        counts.present++;
        break;
      case 'ABSENT':
        counts.absent++;
        break;
      case 'LATE':
        counts.late++;
        break;
      case 'EXCUSED':
        counts.excused++;
        break;
      case 'LEFT_EARLY':
        counts.leftEarly++;
        break;
      default:
        counts.unmarked++;
    }
  }
  return counts;
}

/** PRESENT, ABSENT, LATE, EXCUSED, LEFT_EARLY, and round again. */
export const STATUS_CYCLE: readonly AttendanceStatus[] = ['PRESENT', 'ABSENT', 'LATE', 'EXCUSED', 'LEFT_EARLY'];

/**
 * What a tap on a student does. An unmarked student gets the hint from an acknowledged absence report when there
 * is one (the parent already said so), else PRESENT; after that the status moves one step round the cycle.
 */
export function nextStatus(current: AttendanceStatus | null, suggested: AttendanceStatus | null = null): AttendanceStatus {
  if (current === null) return suggested ?? 'PRESENT';
  const index = STATUS_CYCLE.indexOf(current);
  return STATUS_CYCLE[(index + 1) % STATUS_CYCLE.length] ?? 'PRESENT';
}

/** The mark an edit queues: what the teacher chose, plus the version of the record they saw. */
export function markFor(student: RollStudent, status: AttendanceStatus, minutesLate?: number | null): AttendanceMark {
  const version = student.record?.version;
  return {
    studentId: student.studentId,
    status,
    ...(status === 'LATE' && minutesLate ? { minutesLate } : {}),
    ...(typeof version === 'number' ? { expectedVersion: version } : {}),
  };
}

/** The operation for edits of one roll. `opId` is made by the caller (one per user action). */
export function editOperation(input: {
  opId: string;
  ownerId: string;
  roll: RollSnapshot;
  marks: readonly AttendanceMark[];
  overrideReason?: string | undefined;
  /** The period roll's subject (see `subjectChoice`): carried only on a period slot. */
  subjectId?: string | null | undefined;
  /** The saved group the screen is restricted to. */
  groupId?: string | null | undefined;
}): NewOperation {
  return {
    type: 'ATTENDANCE_MARK',
    opId: input.opId,
    ownerId: input.ownerId,
    schoolId: input.roll.schoolId,
    classId: input.roll.classId,
    baseVersion: input.roll.sessionVersion,
    payload: {
      date: input.roll.date,
      slot: input.roll.slot,
      ...(input.subjectId && isPeriodSlot(input.roll.slot) ? { subjectId: input.subjectId } : {}),
      ...(input.groupId ? { groupId: input.groupId } : {}),
      records: input.marks,
      ...(input.overrideReason ? { overrideReason: input.overrideReason } : {}),
    },
  };
}

/** "Mark all present": every unmarked student, with the absence-report hint instead of PRESENT where there is one. */
export function markAllPresentMarks(students: readonly EffectiveStudent[]): AttendanceMark[] {
  return students.filter((student) => student.status === null).map((student) => markFor(student, student.suggestedStatus ?? 'PRESENT'));
}

/** How many of the unmarked students would be excused rather than present (the confirmation says so). */
export function excusedByHint(students: readonly EffectiveStudent[]): number {
  return students.filter((student) => student.status === null && student.suggestedStatus === 'EXCUSED').length;
}

// --- the lock, the age and what may be edited ------------------------------------------------------------------

/** The roll is locked now: the API said so, or the lock time it gave has passed (works offline). */
export function isRollLocked(roll: Pick<RollSnapshot, 'locked' | 'locksAt'>, nowMs: number): boolean {
  if (roll.locked) return true;
  if (!roll.locksAt) return false;
  const at = Date.parse(roll.locksAt);
  return Number.isFinite(at) && nowMs >= at;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** A cached roll older than this is shown, but not edited: a colleague has probably changed it. */
export const ROLL_EDIT_MAX_AGE_MS = 3 * DAY;
/** The class list a synthetic roll is built from may be older, but not much. */
export const ROSTER_EDIT_MAX_AGE_MS = 7 * DAY;

export type RollTrust = 'fresh' | 'cached' | 'stale';

export function rollTrust(fetchedAt: number, nowMs: number, synthetic: boolean, fromCache: boolean): RollTrust {
  if (!fromCache) return 'fresh';
  const age = nowMs - fetchedAt;
  return age <= (synthetic ? ROSTER_EDIT_MAX_AGE_MS : ROLL_EDIT_MAX_AGE_MS) ? 'cached' : 'stale';
}

export type Editability =
  | { readonly kind: 'open' }
  /** The roll locked: only `mayEditLocked` users may edit, with a reason. */
  | { readonly kind: 'override' }
  | { readonly kind: 'locked' }
  | { readonly kind: 'stale' };

export function editability(roll: RollSnapshot, trust: RollTrust, nowMs: number): Editability {
  if (trust === 'stale') return { kind: 'stale' };
  if (!isRollLocked(roll, nowMs)) return { kind: 'open' };
  return roll.mayEditLocked ? { kind: 'override' } : { kind: 'locked' };
}

export interface Age {
  readonly unit: 'now' | 'minutes' | 'hours' | 'days';
  readonly value: number;
}

export function ageOf(fetchedAt: number, nowMs: number): Age {
  const age = Math.max(0, nowMs - fetchedAt);
  if (age < MINUTE) return { unit: 'now', value: 0 };
  if (age < HOUR) return { unit: 'minutes', value: Math.floor(age / MINUTE) };
  if (age < DAY) return { unit: 'hours', value: Math.floor(age / HOUR) };
  return { unit: 'days', value: Math.floor(age / DAY) };
}

// --- after the server answered ---------------------------------------------------------------------------------

/**
 * The roll with what the server stored for an operation's marks: after APPLIED (and after a CONFLICT, where the
 * stored record is the colleague's), so the screen does not fall back to old values before the next fetch.
 */
export function applyServerState(roll: RollSnapshot, state: unknown): RollSnapshot {
  const { sessionVersion, outcomes } = parseRollState(state);
  if (outcomes.length === 0 && sessionVersion === null) return roll;
  const byStudent = new Map(outcomes.map((outcome) => [outcome.studentId, outcome]));
  return {
    ...roll,
    sessionVersion: sessionVersion ?? roll.sessionVersion,
    students: roll.students.map((student) => {
      const outcome = byStudent.get(student.studentId);
      return outcome ? { ...student, record: { ...outcome.record } } : student;
    }),
  };
}
