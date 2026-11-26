import { addDays, daysBetween, isIsoDate } from "./dates";

/** Absence reports: the guardian's form (pure validation) and the labels of reasons and statuses. */

export const REASONS = ["ILLNESS", "FAMILY", "APPOINTMENT", "OTHER"] as const;
export type AbsenceReason = (typeof REASONS)[number];

export const REPORT_STATUSES = ["SUBMITTED", "ACKNOWLEDGED", "DECLINED", "CANCELLED"] as const;
export type AbsenceStatus = (typeof REPORT_STATUSES)[number];

export const MAX_ABSENCE_NOTE = 500;
export const MAX_DECISION_NOTE = 200;
/** Limits the API enforces (days back, days ahead, days long); the API stays the authority. */
export const MAX_DAYS_BACK = 7;
export const MAX_DAYS_AHEAD = 90;
export const MAX_DAYS_LONG = 61;
/** The family's idea of "today" can be a day off from the school's: window checks leave this much slack. */
export const WINDOW_SLACK_DAYS = 1;

export function isReason(value: unknown): value is AbsenceReason {
  return typeof value === "string" && (REASONS as readonly string[]).includes(value);
}

export function isReportStatus(value: unknown): value is AbsenceStatus {
  return typeof value === "string" && (REPORT_STATUSES as readonly string[]).includes(value);
}

export interface AbsenceForm {
  fromDate: string;
  toDate: string;
  reasonCode: string;
  note: string;
}

/** A form error is a code; the screen maps it to `family.absence.error.<code>`. */
export type AbsenceFormError = "required" | "invalid" | "order" | "tooFarBack" | "tooFarAhead" | "tooLong" | "noteTooLong";

export type AbsenceFormErrors = Partial<Record<keyof AbsenceForm, AbsenceFormError>>;

/**
 * Checks the form before it is sent. `today` is a calendar date the caller knows (the school's, when it is known):
 * the window rules (7 days back, 90 ahead, 61 long) are checked against it as a convenience, the API decides.
 */
export function validateAbsenceForm(form: AbsenceForm, today: string): AbsenceFormErrors {
  const errors: AbsenceFormErrors = {};
  if (!form.fromDate) errors.fromDate = "required";
  else if (!isIsoDate(form.fromDate)) errors.fromDate = "invalid";
  if (!form.toDate) errors.toDate = "required";
  else if (!isIsoDate(form.toDate)) errors.toDate = "invalid";
  if (!form.reasonCode) errors.reasonCode = "required";
  else if (!isReason(form.reasonCode)) errors.reasonCode = "invalid";
  if (form.note.length > MAX_ABSENCE_NOTE) errors.note = "noteTooLong";

  if (!errors.fromDate && !errors.toDate) {
    if (form.fromDate > form.toDate) errors.toDate = "order";
    else if (daysBetween(form.fromDate, form.toDate) + 1 > MAX_DAYS_LONG) errors.toDate = "tooLong";
    else if (isIsoDate(today)) {
      if (form.fromDate < addDays(today, -(MAX_DAYS_BACK + WINDOW_SLACK_DAYS))) errors.fromDate = "tooFarBack";
      else if (form.toDate > addDays(today, MAX_DAYS_AHEAD + WINDOW_SLACK_DAYS)) errors.toDate = "tooFarAhead";
    }
  }
  return errors;
}

export const hasErrors = (errors: object): boolean => Object.keys(errors).length > 0;

/** The request body: dates, reason, and the note only when there is one (trimmed). */
export function buildAbsenceRequest(form: AbsenceForm): { fromDate: string; toDate: string; reasonCode: string; note?: string } {
  const note = form.note.trim();
  return { fromDate: form.fromDate, toDate: form.toDate, reasonCode: form.reasonCode, ...(note ? { note } : {}) };
}

/** A report can be withdrawn by its guardian while it is waiting or acknowledged. */
export const canCancel = (status: string): boolean => status === "SUBMITTED" || status === "ACKNOWLEDGED";

/** A teacher can decide on a report once: only while it is SUBMITTED. */
export const canDecide = (status: string): boolean => status === "SUBMITTED";

/** The inbox filter: the API's `status` parameter (empty: its default, SUBMITTED and ACKNOWLEDGED). */
export type InboxFilter = "PENDING" | "ACKNOWLEDGED" | "DECLINED";

export function inboxStatusParam(filter: InboxFilter): string {
  return filter === "PENDING" ? "SUBMITTED" : filter;
}

/** Number of calendar days a report covers (inclusive). */
export const reportDays = (from: string, to: string): number => daysBetween(from, to) + 1;
