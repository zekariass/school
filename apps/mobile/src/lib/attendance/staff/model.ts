import type { components } from '@school/api-client';

import { addDays, daysBetween } from '@/lib/attendance/dates';
import { isCivilDate } from '@/lib/sync/operations';

/**
 * The manager's staff roll: pure logic (forms, request bodies, counts, the date window). No React Native imports.
 *
 * Rules of the API (M7d-a) that the forms follow: marking only creates (a staff member who already has a record for
 * the day keeps it: changing it is a correction with a reason); minutes late are 1..600 and only with LATE; a note and a
 * reason are at most 200 characters; a day more than 62 days back is refused (400 `staff_attendance.date_too_old`) and
 * so is a day that has not started at the school (409 `staff_attendance.date_in_future`).
 */

export type StaffEntry = components['schemas']['StaffDayEntry'];
export type StaffRecord = components['schemas']['StaffRecordView'];
export type StaffMarkInput = components['schemas']['StaffMarkInput'];
export type StaffDayRequest = components['schemas']['StaffDayRequest'];
export type StaffCorrectionRequest = components['schemas']['StaffCorrectionRequest'];

export const STAFF_STATUSES = ['PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'EXCUSED'] as const;
export type StaffStatus = (typeof STAFF_STATUSES)[number];

export const STAFF_NOTE_MAX = 200;
export const STAFF_REASON_MAX = 200;
export const STAFF_MINUTES_MAX = 600;
/** How far back the API accepts a day (`staff_attendance.date_too_old` beyond). */
export const STAFF_MAX_DAYS_BACK = 62;

export const isStaffStatus = (value: unknown): value is StaffStatus => typeof value === 'string' && (STAFF_STATUSES as readonly string[]).includes(value);

// --- the date window ---------------------------------------------------------------------------------------------

/** A day the roll can be opened for: not after the school's today, at most 62 days before it. */
export function isStaffDate(date: string, today: string): boolean {
  if (!isCivilDate(date) || !isCivilDate(today)) return false;
  return date <= today && daysBetween(date, today) <= STAFF_MAX_DAYS_BACK;
}
export const canGoEarlierStaff = (date: string, today: string): boolean => isStaffDate(addDays(date, -1), today);
export const canGoLaterStaff = (date: string, today: string): boolean => isStaffDate(addDays(date, 1), today);

// --- marking a day ----------------------------------------------------------------------------------------------

/** Minutes as typed: a whole number 1..600, else null. */
export function minutesOf(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d{1,3}$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return value >= 1 && value <= STAFF_MINUTES_MAX ? value : null;
}

export interface MarkForm {
  readonly status: StaffStatus;
  /** Only read for LATE. */
  readonly minutes: string;
  readonly note: string;
}

export const emptyMarkForm: MarkForm = { status: 'PRESENT', minutes: '', note: '' };

export interface MarkCheck {
  readonly valid: boolean;
  /** LATE with text that is not 1..600. */
  readonly minutesInvalid: boolean;
  readonly minutesLate: number | null;
}

export function checkMark(form: Pick<MarkForm, 'status' | 'minutes'>): MarkCheck {
  const late = form.status === 'LATE';
  const minutesLate = late ? minutesOf(form.minutes) : null;
  const minutesInvalid = late && form.minutes.trim().length > 0 && minutesLate === null;
  return { valid: !minutesInvalid, minutesInvalid, minutesLate };
}

/** One staff member's mark for `PUT .../days/{date}`; null when the form is not valid. */
export function markInput(staffId: string, form: MarkForm): StaffMarkInput | null {
  const check = checkMark(form);
  if (!check.valid) return null;
  const note = form.note.trim().slice(0, STAFF_NOTE_MAX);
  return {
    staffId,
    status: form.status,
    ...(check.minutesLate !== null ? { minutesLate: check.minutesLate } : {}),
    ...(note.length > 0 ? { note } : {}),
  };
}

/** The body of `PUT .../days/{date}`: the marks, and/or "everybody else present". Null when it would say nothing. */
export function dayRequest(records: readonly StaffMarkInput[], markRestPresent: boolean): StaffDayRequest | null {
  if (records.length === 0 && !markRestPresent) return null;
  return { ...(records.length > 0 ? { records: [...records] } : {}), ...(markRestPresent ? { markRestPresent: true } : {}) };
}

export interface StaffCounts {
  readonly present: number;
  readonly absent: number;
  readonly late: number;
  readonly halfDay: number;
  readonly excused: number;
  readonly unmarked: number;
  readonly total: number;
}

export function countStaff(entries: readonly Pick<StaffEntry, 'record'>[]): StaffCounts {
  const counts = { present: 0, absent: 0, late: 0, halfDay: 0, excused: 0, unmarked: 0, total: entries.length };
  for (const entry of entries) {
    switch (entry.record?.status) {
      case 'PRESENT':
        counts.present++;
        break;
      case 'ABSENT':
        counts.absent++;
        break;
      case 'LATE':
        counts.late++;
        break;
      case 'HALF_DAY':
        counts.halfDay++;
        break;
      case 'EXCUSED':
        counts.excused++;
        break;
      default:
        counts.unmarked++;
    }
  }
  return counts;
}

// --- correcting a record ------------------------------------------------------------------------------------------

export interface CorrectionForm extends MarkForm {
  /** Required (at most 200): why the record was wrong. Kept in the append-only history. */
  readonly reason: string;
}

/** The form for a record: its own status, minutes and note, and no reason yet. */
export function correctionFormFor(record: Pick<StaffRecord, 'status' | 'minutesLate' | 'note'>): CorrectionForm {
  return {
    status: record.status,
    minutes: record.minutesLate ? String(record.minutesLate) : '',
    note: record.note ?? '',
    reason: '',
  };
}

export type CorrectionProblem = 'reason' | 'minutes' | 'unchanged';

export type CorrectionCheck =
  | { readonly ok: true; readonly request: StaffCorrectionRequest }
  | { readonly ok: false; readonly problem: CorrectionProblem };

/**
 * The body of `POST .../records/{id}/corrections`. A reason is required; nothing is sent when nothing changed (the API
 * answers 400 `staff_attendance.nothing_to_correct`). The note is sent only when it changed: absent keeps it, empty text
 * clears it. `expectedVersion` is the version the manager read, so a clash with another manager is a 409.
 */
export function correctionRequest(record: Pick<StaffRecord, 'status' | 'minutesLate' | 'note' | 'version'>, form: CorrectionForm): CorrectionCheck {
  const reason = form.reason.trim();
  if (reason.length === 0) return { ok: false, problem: 'reason' };
  const check = checkMark(form);
  if (!check.valid) return { ok: false, problem: 'minutes' };

  const oldNote = record.note ?? '';
  const newNote = form.note.trim().slice(0, STAFF_NOTE_MAX);
  const noteChanged = newNote !== oldNote.trim();
  const minutesChanged = (check.minutesLate ?? null) !== (record.minutesLate ?? null);
  if (form.status === record.status && !minutesChanged && !noteChanged) return { ok: false, problem: 'unchanged' };

  return {
    ok: true,
    request: {
      status: form.status,
      ...(check.minutesLate !== null ? { minutesLate: check.minutesLate } : {}),
      ...(noteChanged ? { note: newNote } : {}),
      reason: reason.slice(0, STAFF_REASON_MAX),
      expectedVersion: record.version ?? 0,
    },
  };
}
