import type { components } from "@school/api-client";
import { isProblemDetail } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { dashboardQuery, reportQuery, reportQueryString, type ReportFilters } from "./report";
import { isWriteResult, type RollRequest, type WriteResult } from "./roll";

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
  roll: (schoolId: string, classId: string, date: string | null, slot: string | null) =>
    ["school", schoolId, "attendance", "roll", classId, date ?? "today", slot ?? "default"] as const,
  allRolls: (schoolId: string, classId: string) => ["school", schoolId, "attendance", "roll", classId] as const,
  dashboard: (schoolId: string, date: string | null, slot: string | null) =>
    ["school", schoolId, "attendance", "dashboard", date ?? "today", slot ?? "default"] as const,
  allDashboards: (schoolId: string) => ["school", schoolId, "attendance", "dashboard"] as const,
  report: (schoolId: string, filters: ReportFilters) => ["school", schoolId, "attendance", "report", reportQuery(filters)] as const,
  history: (schoolId: string, studentId: string) => ["school", schoolId, "attendance", "history", studentId] as const,
  classAbsences: (schoolId: string, classId: string, status: string) =>
    ["school", schoolId, "attendance", "absences", "class", classId, status] as const,
  allClassAbsences: (schoolId: string, classId: string) => ["school", schoolId, "attendance", "absences", "class", classId] as const,
  studentAbsences: (schoolId: string, studentId: string) => ["school", schoolId, "attendance", "absences", "student", studentId] as const,
};

export const fetchRoll = async (schoolId: string, classId: string, date: string | null, slot: string | null) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/attendance", {
      params: { path: { schoolId, classId }, query: { ...(date ? { date } : {}), ...(slot ? { slot } : {}) } },
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

export const fetchDashboard = async (schoolId: string, date: string | null, slot: string | null, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/attendance/dashboard", {
      params: { path: { schoolId }, query: { ...dashboardQuery(date, slot), cursor, limit: DASHBOARD_PAGE } },
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
export async function downloadAttendanceCsv(schoolId: string, filters: ReportFilters): Promise<Blob> {
  const response = await fetch(
    `${window.location.origin}/api/bff/api/v1/schools/${encodeURIComponent(schoolId)}/attendance/report.csv${reportQueryString(filters)}`,
    {
      cache: "no-store",
      headers: { Accept: "text/csv, application/problem+json", "Accept-Language": document.documentElement.lang || "en" },
    },
  );
  if (!response.ok) {
    let problem: unknown;
    try {
      problem = await response.json();
    } catch {
      problem = undefined;
    }
    throw new ApiRequestError(response.status, isProblemDetail(problem) ? problem : undefined);
  }
  return response.blob();
}

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
