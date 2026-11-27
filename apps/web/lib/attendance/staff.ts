import type { components } from "@school/api-client";
import { ApiRequestError } from "@/lib/api-error";
import { isIsoDate } from "./dates";
import { MAX_LATE, MIN_LATE } from "./status";

/**
 * Staff attendance (M7d), manager screens: pure model of the day's marks, the correction form and the summaries'
 * query strings. Names and notes of staff are personal data: they live in component memory and the TanStack cache
 * only. There is no teacher-facing staff view; the API answers 403/404 to everybody who may not read.
 */

type S = components["schemas"];
export type StaffDayRoll = S["StaffDayRoll"];
export type StaffDayEntry = S["StaffDayEntry"];
export type StaffRecord = S["StaffRecordView"];
export type StaffRecordDetail = S["StaffRecordDetail"];
export type StaffCorrection = S["StaffCorrectionView"];
export type StaffDayRequest = S["StaffDayRequest"];
export type StaffMarkInput = S["StaffMarkInput"];
export type StaffCorrectionRequest = S["StaffCorrectionRequest"];

export const STAFF_STATUSES = ["PRESENT", "ABSENT", "LATE", "HALF_DAY", "EXCUSED"] as const;
export type StaffStatus = (typeof STAFF_STATUSES)[number];

export const MAX_STAFF_NOTE = 200;
export const MAX_STAFF_REASON = 200;
/** The API refuses a mark request over this many records. */
export const MAX_STAFF_RECORDS = 500;
/** The API refuses to mark a day further back than this (400 `staff_attendance.date_too_old`). */
export const STAFF_DAYS_BACK = 62;

/** The counter of each status in the day's summary. */
export const STATUS_COUNT_KEY = { PRESENT: "present", ABSENT: "absent", LATE: "late", HALF_DAY: "halfDay", EXCUSED: "excused" } as const;

export const isStaffStatus = (value: unknown): value is StaffStatus => typeof value === "string" && (STAFF_STATUSES as readonly string[]).includes(value);

/** Minutes only go with LATE. */
export const staffTakesMinutes = (status: StaffStatus): boolean => status === "LATE";

// --- failures ------------------------------------------------------------------------------------------------------

/** Why the staff screens cannot show anything, from the API's own answer. */
export type StaffFailure = "disabled" | "featureOff" | "denied" | "other";

/**
 * 403 `staff_attendance.disabled` (the setting `attendance.staff.enabled` is off, the default), 403
 * `attendance.feature_disabled` (the school's plan has no attendance), 403 `staff_attendance.access_denied` and 404
 * (the school or record cannot be seen) are answers the screen explains; everything else shows the API's detail.
 */
export function staffFailure(error: unknown): StaffFailure {
  if (!(error instanceof ApiRequestError)) return "other";
  const code = error.problem?.code;
  if (code === "staff_attendance.disabled") return "disabled";
  if (code === "attendance.feature_disabled") return "featureOff";
  if (code === "staff_attendance.access_denied" || error.status === 404 || error.status === 403) return "denied";
  return "other";
}

/** 409 `staff_attendance.version_conflict`: somebody corrected the record meanwhile. */
export const isVersionConflict = (error: unknown): boolean => error instanceof ApiRequestError && error.problem?.code === "staff_attendance.version_conflict";

// --- marking a day -------------------------------------------------------------------------------------------------

export interface StaffMark {
  status: StaffStatus;
  /** Only with LATE, 1..600. */
  minutesLate?: number;
  note?: string;
}

/** Unsaved marks by staff id: only people the manager touched and who have no record yet. In memory only. */
export type StaffDrafts = Readonly<Record<string, StaffMark>>;

/** Clean form: minutes only with LATE, a trimmed note without empty strings. */
export function normalizeStaffMark(mark: StaffMark): StaffMark {
  const note = mark.note?.trim().slice(0, MAX_STAFF_NOTE);
  return {
    status: mark.status,
    ...(staffTakesMinutes(mark.status) && mark.minutesLate !== undefined ? { minutesLate: mark.minutesLate } : {}),
    ...(note ? { note } : {}),
  };
}

/** A staff member can be marked only while the day has no record for them (a change is a correction with a reason). */
export const canMark = (entry: Pick<StaffDayEntry, "record">): boolean => !entry.record;

export function setStaffStatus(drafts: StaffDrafts, staffId: string, status: StaffStatus): StaffDrafts {
  const current = drafts[staffId];
  return { ...drafts, [staffId]: normalizeStaffMark({ ...(current?.note ? { note: current.note } : {}), ...(staffTakesMinutes(status) && current?.minutesLate ? { minutesLate: current.minutesLate } : {}), status }) };
}

export function setStaffMinutes(drafts: StaffDrafts, staffId: string, minutesLate: number | undefined): StaffDrafts {
  const current = drafts[staffId];
  if (!current) return drafts;
  return {
    ...drafts,
    [staffId]: normalizeStaffMark({ status: current.status, ...(current.note ? { note: current.note } : {}), ...(minutesLate !== undefined ? { minutesLate } : {}) }),
  };
}

export function setStaffNote(drafts: StaffDrafts, staffId: string, note: string): StaffDrafts {
  const current = drafts[staffId];
  if (!current) return drafts;
  return { ...drafts, [staffId]: { ...current, note } };
}

export function clearStaffDraft(drafts: StaffDrafts, staffId: string): StaffDrafts {
  const next = { ...drafts };
  delete next[staffId];
  return next;
}

/** True when the mark is one the API accepts (minutes within 1..600 and only with LATE; note within 200). */
export function staffMarkValid(mark: StaffMark): boolean {
  if (mark.minutesLate !== undefined && (!staffTakesMinutes(mark.status) || mark.minutesLate < MIN_LATE || mark.minutesLate > MAX_LATE)) return false;
  return (mark.note?.trim().length ?? 0) <= MAX_STAFF_NOTE;
}

export const draftCount = (drafts: StaffDrafts): number => Object.keys(drafts).length;

/**
 * The request that saves the day: the manager's marks (only for people without a record; the API would answer
 * EXISTS and keep the stored record anyway) and optionally "mark the rest present". `null` when it would send nothing
 * (the API answers 400 `staff_attendance.nothing_to_record`).
 */
export function buildDayRequest(entries: readonly StaffDayEntry[], drafts: StaffDrafts, markRestPresent: boolean): StaffDayRequest | null {
  const records: StaffMarkInput[] = [];
  for (const entry of entries) {
    const draft = drafts[entry.staffId];
    if (!draft || !canMark(entry)) continue;
    const mark = normalizeStaffMark(draft);
    records.push({
      staffId: entry.staffId,
      status: mark.status,
      ...(mark.minutesLate !== undefined ? { minutesLate: mark.minutesLate } : {}),
      ...(mark.note ? { note: mark.note } : {}),
    });
  }
  if (records.length === 0 && !markRestPresent) return null;
  return { ...(records.length > 0 ? { records } : {}), ...(markRestPresent ? { markRestPresent: true } : {}) };
}

/** Staff without a record on the loaded pages (what "mark the rest present" would cover, at least). */
export const unmarkedCount = (entries: readonly StaffDayEntry[]): number => entries.filter(canMark).length;

export interface DayCounts {
  staff: number;
  marked: number;
  unmarked: number;
  present: number;
  absent: number;
  late: number;
  halfDay: number;
  excused: number;
}

/** What the day's summary shows: the stored records of the loaded staff. */
export function dayCounts(entries: readonly StaffDayEntry[]): DayCounts {
  const out: DayCounts = { staff: entries.length, marked: 0, unmarked: 0, present: 0, absent: 0, late: 0, halfDay: 0, excused: 0 };
  for (const entry of entries) {
    if (!entry.record) {
      out.unmarked++;
      continue;
    }
    out.marked++;
    out[STATUS_COUNT_KEY[entry.record.status]]++;
  }
  return out;
}

// --- corrections ---------------------------------------------------------------------------------------------------

export interface CorrectionForm {
  status: StaffStatus;
  /** The text of the minutes field. */
  minutes: string;
  note: string;
  reason: string;
}

/** The form starts from the stored record. */
export function correctionFormOf(record: Pick<StaffRecord, "status" | "minutesLate" | "note">): CorrectionForm {
  return { status: record.status, minutes: record.minutesLate != null ? String(record.minutesLate) : "", note: record.note ?? "", reason: "" };
}

/** A whole number of minutes 1..600 from the field's text, or undefined when empty or unusable. */
export function parseStaffMinutes(text: string): number | undefined {
  const trimmed = text.trim();
  if (!/^\d{1,4}$/.test(trimmed)) return undefined;
  const n = Number(trimmed);
  return n >= MIN_LATE && n <= MAX_LATE ? n : undefined;
}

/** Field errors as `validation.*` keys of the shared catalog (or the screen's own keys); empty when the form can be sent. */
export type CorrectionErrors = Partial<Record<"minutes" | "note" | "reason" | "nothing", "invalid" | "tooLong" | "required" | "nothingChanged">>;

export function correctionErrors(record: Pick<StaffRecord, "status" | "minutesLate" | "note">, form: CorrectionForm): CorrectionErrors {
  const errors: CorrectionErrors = {};
  if (staffTakesMinutes(form.status) && form.minutes.trim() !== "" && parseStaffMinutes(form.minutes) === undefined) errors.minutes = "invalid";
  if (form.note.trim().length > MAX_STAFF_NOTE) errors.note = "tooLong";
  const reason = form.reason.trim();
  if (reason === "") errors.reason = "required";
  else if (reason.length > MAX_STAFF_REASON) errors.reason = "tooLong";
  if (Object.keys(errors).length === 0 && !correctionChanges(record, form)) errors.nothing = "nothingChanged";
  return errors;
}

const minutesOfForm = (form: CorrectionForm): number | undefined => (staffTakesMinutes(form.status) ? parseStaffMinutes(form.minutes) : undefined);

/** True when the form differs from the stored record (the API answers 400 `nothing_to_correct` otherwise). */
export function correctionChanges(record: Pick<StaffRecord, "status" | "minutesLate" | "note">, form: CorrectionForm): boolean {
  return (
    form.status !== record.status ||
    (minutesOfForm(form) ?? null) !== (record.minutesLate ?? null) ||
    form.note.trim() !== (record.note ?? "").trim()
  );
}

/**
 * The correction request: the new status and minutes (only with LATE), the note only when it changed (omitted keeps
 * the stored one, an empty string clears it), the reason and the version the manager read. `null` without a version
 * (the API requires it).
 */
export function buildCorrection(record: Pick<StaffRecord, "status" | "minutesLate" | "note" | "version">, form: CorrectionForm): StaffCorrectionRequest | null {
  if (record.version == null) return null;
  const minutes = minutesOfForm(form);
  const note = form.note.trim().slice(0, MAX_STAFF_NOTE);
  const noteChanged = note !== (record.note ?? "").trim();
  return {
    status: form.status,
    ...(minutes !== undefined ? { minutesLate: minutes } : {}),
    ...(noteChanged ? { note } : {}),
    reason: form.reason.trim().slice(0, MAX_STAFF_REASON),
    expectedVersion: record.version,
  };
}

// --- summaries -----------------------------------------------------------------------------------------------------

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** True for "yyyy-MM". */
export const isMonth = (value: string | null | undefined): value is string => !!value && MONTH.test(value);

/** The month of a school-local date "yyyy-MM-dd". */
export const monthOf = (date: string): string => date.slice(0, 7);

export function staffDailyQuery(from: string | undefined, to: string | undefined): { from?: string; to?: string } {
  return { ...(isIsoDate(from) ? { from } : {}), ...(isIsoDate(to) ? { to } : {}) };
}

export const staffMonthlyQuery = (month: string | undefined): { month?: string } => (isMonth(month) ? { month } : {});

/** CSV path of a staff summary relative to `/schools/{id}/staff-attendance/reports/` and its query string. */
export function staffCsvPath(kind: "daily" | "monthly", filters: { from?: string; to?: string; month?: string }): string {
  const query = kind === "daily" ? staffDailyQuery(filters.from, filters.to) : staffMonthlyQuery(filters.month);
  const params = new URLSearchParams(query as Record<string, string>);
  return `${kind}.csv${params.size > 0 ? `?${params.toString()}` : ""}`;
}

export function staffCsvFileName(kind: "daily" | "monthly", filters: { from?: string; to?: string; month?: string }): string {
  if (kind === "monthly") return `staff-attendance-${isMonth(filters.month) ? filters.month : "month"}.csv`;
  const range = [filters.from, filters.to].filter(Boolean).join("_");
  return `staff-attendance-daily${range ? `-${range}` : ""}.csv`;
}
