import type { components } from "@school/api-client";
import { isProblemDetail } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError, retryAfterOf, unwrap } from "@/lib/api-error";
import { periodQuery, periodQueryString, type PeriodFilters } from "./period-report";
import { dashboardQuery, reportQuery, reportQueryString, type ReportFilters } from "./report";
import { isWriteResult, type RollRequest, type WriteResult } from "./roll";
import { staffCsvPath, staffDailyQuery, staffMonthlyQuery, type StaffCorrectionRequest, type StaffDayRequest } from "./staff";

type S = components["schemas"];

export type DashboardView = S["AttendanceDashboard"];
export type DashboardClass = S["DashboardClass"];
export type ReportRow = S["AttendanceReportRow"];
export type HistoryItem = S["AttendanceHistoryItem"];
export type AbsenceReport = S["AbsenceReportView"];
export type AbsenceReportRequest = S["AbsenceReportRequest"];

const api = () => getBrowserApi();

/**
 * Query keys of the attendance screens, under the school's key so one invalidation can refresh everything.
 * Everything here is children's data: kept in memory only (never persisted, never in the URL).
 */
export const ak = {
  all: (schoolId: string) => ["school", schoolId, "attendance"] as const,
  roll: (schoolId: string, classId: string, date: string | null, slot: string | null, groupId: string | null = null) =>
    ["school", schoolId, "attendance", "roll", classId, date ?? "today", slot ?? "default", groupId ?? "all"] as const,
  allRolls: (schoolId: string, classId: string) => ["school", schoolId, "attendance", "roll", classId] as const,
  dashboard: (schoolId: string, date: string | null, slot: string | null, subjectId: string | null = null) =>
    ["school", schoolId, "attendance", "dashboard", date ?? "today", slot ?? "default", subjectId ?? "any"] as const,
  allDashboards: (schoolId: string) => ["school", schoolId, "attendance", "dashboard"] as const,
  /** The slots the school enabled, read from the dashboard's answer. */
  slots: (schoolId: string) => ["school", schoolId, "attendance", "slots"] as const,
  periodReport: (schoolId: string, filters: PeriodFilters) => ["school", schoolId, "attendance", "period-report", periodQuery(filters)] as const,
  /** Staff attendance: names and notes of staff, memory only like the rest of this root. */
  staffDay: (schoolId: string, date: string) => ["school", schoolId, "attendance", "staff", "day", date] as const,
  allStaffDays: (schoolId: string) => ["school", schoolId, "attendance", "staff", "day"] as const,
  staffRecord: (schoolId: string, recordId: string) => ["school", schoolId, "attendance", "staff", "record", recordId] as const,
  staffMonthly: (schoolId: string, month: string) => ["school", schoolId, "attendance", "staff", "monthly", month] as const,
  staffDaily: (schoolId: string, from: string, to: string) => ["school", schoolId, "attendance", "staff", "daily", from, to] as const,  report: (schoolId: string, filters: ReportFilters) => ["school", schoolId, "attendance", "report", reportQuery(filters)] as const,
  history: (schoolId: string, studentId: string) => ["school", schoolId, "attendance", "history", studentId] as const,
  classAbsences: (schoolId: string, classId: string, status: string) =>
    ["school", schoolId, "attendance", "absences", "class", classId, status] as const,
  allClassAbsences: (schoolId: string, classId: string) => ["school", schoolId, "attendance", "absences", "class", classId] as const,
  studentAbsences: (schoolId: string, studentId: string) => ["school", schoolId, "attendance", "absences", "student", studentId] as const,
};

export const fetchRoll = async (schoolId: string, classId: string, date: string | null, slot: string | null, groupId: string | null = null) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/attendance", {
      params: { path: { schoolId, classId }, query: { ...(date ? { date } : {}), ...(slot ? { slot } : {}), ...(groupId ? { groupId } : {}) } },
    }),
  );

/**
 * Saves a roll. The API answers 200, or 409 with the same body when some marks conflicted (the others were
 * written): both are results the screen handles. Any other failure throws `ApiRequestError` with the problem.
 */
export async function putRoll(schoolId: string, classId: string, body: RollRequest): Promise<WriteResult> {
  const result = await api().PUT("/api/v1/schools/{schoolId}/classes/{classId}/attendance", {
    params: { path: { schoolId, classId } },
    body,
  });
  if (result.data) return result.data;
  const answer: unknown = result.error;
  if (result.response.status === 409 && isWriteResult(answer)) return answer;
  throw new ApiRequestError(result.response.status, isProblemDetail(answer) ? answer : undefined);
}

export const DASHBOARD_PAGE = 30;

export const fetchDashboard = async (schoolId: string, date: string | null, slot: string | null, cursor?: string, subjectId: string | null = null, limit = DASHBOARD_PAGE) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/attendance/dashboard", {
      params: { path: { schoolId }, query: { ...dashboardQuery(date, slot, subjectId), cursor, limit } },
    }),
  );

export const PERIOD_REPORT_PAGE = 50;

export const fetchPeriodReport = async (schoolId: string, filters: PeriodFilters, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/attendance/period-report", {
      params: { path: { schoolId }, query: { ...periodQuery(filters), cursor, limit: PERIOD_REPORT_PAGE } },
    }),
  );

export const REPORT_PAGE = 50;

export const fetchAttendanceReport = async (schoolId: string, filters: ReportFilters, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/attendance/report", {
      params: { path: { schoolId }, query: { ...reportQuery(filters), cursor, limit: REPORT_PAGE } },
    }),
  );

/**
 * Downloads the report as CSV through the BFF (never a link with a token). `no-store`: the file is per user and
 * audited, so no cache may keep or replay it. Throws `ApiRequestError` with the API's problem on failure
 * (403 no access, 429 `attendance.export_rate_limited`).
 */
export const downloadAttendanceCsv = (schoolId: string, filters: ReportFilters): Promise<Blob> =>
  downloadCsv(`/schools/${encodeURIComponent(schoolId)}/attendance/report.csv${reportQueryString(filters)}`);

/** The per-period summary as CSV (audited; shares the 10 per 10 minutes limit of the other roll exports). */
export const downloadPeriodCsv = (schoolId: string, filters: PeriodFilters): Promise<Blob> =>
  downloadCsv(`/schools/${encodeURIComponent(schoolId)}/attendance/period-report.csv${periodQueryString(filters)}`);

/** The staff daily or monthly summary as CSV (audited; its own limit of 10 per 10 minutes). */
export const downloadStaffCsv = (schoolId: string, kind: "daily" | "monthly", filters: { from?: string; to?: string; month?: string }): Promise<Blob> =>
  downloadCsv(`/schools/${encodeURIComponent(schoolId)}/staff-attendance/reports/${staffCsvPath(kind, filters)}`);

/**
 * One CSV download through the BFF (`path` under `/api/v1`, query string included): the browser never holds a token.
 * `no-store` because the file is per user and audited. A refusal throws `ApiRequestError` with the API's problem and,
 * for 429, the `Retry-After` seconds.
 */
async function downloadCsv(path: string): Promise<Blob> {
  const response = await fetch(`${window.location.origin}/api/bff/api/v1${path}`, {
    cache: "no-store",
    headers: { Accept: "text/csv, application/problem+json", "Accept-Language": document.documentElement.lang || "en" },
  });
  if (!response.ok) {
    let problem: unknown;
    try {
      problem = await response.json();
    } catch {
      problem = undefined;
    }
    throw new ApiRequestError(response.status, isProblemDetail(problem) ? problem : undefined, retryAfterOf(response));
  }
  return response.blob();
}

// --- staff attendance ---------------------------------------------------------------------------------------------

export const STAFF_PAGE = 50;

export const fetchStaffDay = async (schoolId: string, date: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/staff-attendance/days/{date}", {
      params: { path: { schoolId, date }, query: { cursor, limit: STAFF_PAGE } },
    }),
  );

/** Marks staff for a day. Creates only: a staff member who already has a record keeps it (outcome EXISTS). No `Idempotency-Key`: a repeat is harmless. */
export const markStaffDay = async (schoolId: string, date: string, body: StaffDayRequest) =>
  unwrap(await api().PUT("/api/v1/schools/{schoolId}/staff-attendance/days/{date}", { params: { path: { schoolId, date } }, body }));

export const fetchStaffRecord = async (schoolId: string, recordId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/staff-attendance/records/{recordId}", { params: { path: { schoolId, recordId } } }));

/** Corrects a record (reason required, appended to its history). `@Idempotent` on the API: one key per submission. */
export const correctStaffRecord = async (schoolId: string, recordId: string, body: StaffCorrectionRequest, idempotencyKey: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/staff-attendance/records/{recordId}/corrections", {
      params: { path: { schoolId, recordId } },
      body,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
  );

export const fetchStaffDaily = async (schoolId: string, from: string, to: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/staff-attendance/reports/daily", {
      params: { path: { schoolId }, query: staffDailyQuery(from, to) },
    }),
  );

export const fetchStaffMonthly = async (schoolId: string, month: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/staff-attendance/reports/monthly", {
      params: { path: { schoolId }, query: { ...staffMonthlyQuery(month), cursor, limit: STAFF_PAGE } },
    }),
  );

export const HISTORY_PAGE = 20;

export const fetchHistory = async (schoolId: string, studentId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/attendance", {
      params: { path: { schoolId, studentId }, query: { cursor, limit: HISTORY_PAGE } },
    }),
  );

export const ABSENCE_PAGE = 30;

export const fetchClassAbsences = async (schoolId: string, classId: string, status: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/absence-reports", {
      params: { path: { schoolId, classId }, query: { status, cursor, limit: ABSENCE_PAGE } },
    }),
  );

export const fetchStudentAbsences = async (schoolId: string, studentId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/absence-reports", {
      params: { path: { schoolId, studentId }, query: { cursor, limit: 20 } },
    }),
  );

/** Files an absence report. The `Idempotency-Key` makes a repeated submit answer with the first report. */
export async function reportAbsence(schoolId: string, studentId: string, body: AbsenceReportRequest, idempotencyKey: string): Promise<AbsenceReport> {
  return unwrap(
    await api().POST("/api/v1/schools/{schoolId}/students/{studentId}/absence-reports", {
      params: { path: { schoolId, studentId } },
      body,
      headers: { "Idempotency-Key": idempotencyKey },
    }),
  );
}

export type Decision = "acknowledge" | "decline";

export async function decideAbsence(schoolId: string, reportId: string, decision: Decision): Promise<AbsenceReport> {
  const params = { params: { path: { schoolId, reportId } }, body: {} };
  return unwrap(
    decision === "acknowledge"
      ? await api().POST("/api/v1/schools/{schoolId}/absence-reports/{reportId}/acknowledge", params)
      : await api().POST("/api/v1/schools/{schoolId}/absence-reports/{reportId}/decline", params),
  );
}

export async function cancelAbsence(schoolId: string, reportId: string): Promise<AbsenceReport> {
  return unwrap(
    await api().POST("/api/v1/schools/{schoolId}/absence-reports/{reportId}/cancel", {
      params: { path: { schoolId, reportId } },
    }),
  );
}
