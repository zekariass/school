import { addDays, daysBetween } from '@/lib/attendance/dates';

/**
 * A guardian's absence report: the same rules the API enforces (dates are school-local; at most 7 days back, 90
 * ahead and 61 days long; reason ILLNESS, FAMILY, APPOINTMENT or OTHER; note up to 500 characters), checked on the
 * phone first so most mistakes never need a round trip. The API stays the authority (and the only judge of the
 * school's own "today"): a device in another time zone is allowed one day of slack.
 */

export const REASON_CODES = ['ILLNESS', 'FAMILY', 'APPOINTMENT', 'OTHER'] as const;
export type ReasonCode = (typeof REASON_CODES)[number];

export const isReasonCode = (value: unknown): value is ReasonCode => typeof value === 'string' && (REASON_CODES as readonly string[]).includes(value);

export const MAX_DAYS_BACK = 7;
export const MAX_DAYS_AHEAD = 90;
export const MAX_DAYS_LONG = 61;
export const MAX_NOTE_LENGTH = 500;
/** The decline note a teacher may add. */
export const MAX_DECISION_NOTE_LENGTH = 200;

export interface AbsenceForm {
  readonly fromDate: string;
  readonly toDate: string;
  readonly reasonCode: ReasonCode;
  readonly note: string;
}

export type AbsenceFormError = 'order' | 'tooLong' | 'tooEarly' | 'tooFar' | 'noteTooLong';

/** Empty means the form may be sent. `today` is the device's date (the school's may differ by a day). */
export function validateAbsenceForm(form: AbsenceForm, today: string): AbsenceFormError[] {
  const errors: AbsenceFormError[] = [];
  const span = daysBetween(form.fromDate, form.toDate);
  if (!(span >= 0)) errors.push('order');
  else if (span + 1 > MAX_DAYS_LONG) errors.push('tooLong');
  if (daysBetween(today, form.fromDate) < -(MAX_DAYS_BACK + 1)) errors.push('tooEarly');
  if (daysBetween(today, form.toDate) > MAX_DAYS_AHEAD + 1) errors.push('tooFar');
  if (form.note.trim().length > MAX_NOTE_LENGTH) errors.push('noteTooLong');
  return errors;
}

export function initialAbsenceForm(today: string): AbsenceForm {
  return { fromDate: today, toDate: today, reasonCode: 'ILLNESS', note: '' };
}

/** Moving the first day past the last one drags the last one along; the range never inverts. */
export function withFromDate(form: AbsenceForm, fromDate: string): AbsenceForm {
  return { ...form, fromDate, toDate: form.toDate < fromDate ? fromDate : form.toDate };
}

/** The request body (the note only when there is one). */
export function toRequestBody(form: AbsenceForm): { fromDate: string; toDate: string; reasonCode: string; note?: string } {
  const note = form.note.trim();
  return { fromDate: form.fromDate, toDate: form.toDate, reasonCode: form.reasonCode, ...(note ? { note } : {}) };
}

/** The furthest day a stepper may go: backwards and forwards from the device's today. */
export function absenceDateBounds(today: string): { min: string; max: string } {
  return { min: addDays(today, -MAX_DAYS_BACK), max: addDays(today, MAX_DAYS_AHEAD) };
}

export type ReportStatus = 'SUBMITTED' | 'ACKNOWLEDGED' | 'DECLINED' | 'CANCELLED';

export function reportStatus(value: string): ReportStatus | 'UNKNOWN' {
  return value === 'SUBMITTED' || value === 'ACKNOWLEDGED' || value === 'DECLINED' || value === 'CANCELLED' ? value : 'UNKNOWN';
}

/** A guardian may withdraw a report that is waiting or acknowledged. */
export const canCancel = (status: string): boolean => status === 'SUBMITTED' || status === 'ACKNOWLEDGED';
/** A teacher decides a report once: only a waiting one. */
export const canDecide = (status: string): boolean => status === 'SUBMITTED';
