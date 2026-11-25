import type { components } from "@school/api-client";
import { isProblemDetail } from "@school/api-client";
import { ApiRequestError, ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { reportQuery, type ReportFilters } from "./report";
import type { AwardBody } from "./award-op";

type S = components["schemas"];

export type PointCategory = S["PointCategoryView"];
export type ClassPoints = S["ClassPoints"];
export type ClassPointsStudent = S["StudentTotal"];
export type EngagementSettings = S["EngagementSettingsView"];
export type AwardResult = S["AwardResult"];
export type Leaderboard = S["Leaderboard"];
export type StudentPoints = S["StudentPoints"];
export type PointsSection = S["Section"];
export type CategoryTotal = S["CategoryTotal"];
export type TimelineItem = S["TimelineItem"];
export type BadgeDefinition = S["BadgeView"];
export type StudentBadge = S["StudentBadgeView"];
export type ReportRow = S["ReportRow"];
export type ActivityTemplate = S["TemplateView"];
export type TemplateType = ActivityTemplate["templateType"];
export type TemplateVisibility = ActivityTemplate["visibility"];

const api = () => getBrowserApi();

/** Query keys of the points screens, under the school's key so one invalidation can refresh everything. */
export const pk = {
  categories: (schoolId: string, classId: string | null, context: string | null = null) =>
    ["school", schoolId, "points", "categories", classId ?? "school", context ?? "all"] as const,
  allCategories: (schoolId: string) => ["school", schoolId, "points", "categories"] as const,
  overview: (schoolId: string, classId: string) => ["school", schoolId, "points", "overview", classId] as const,
  leaderboard: (schoolId: string, classId: string) => ["school", schoolId, "points", "leaderboard", classId] as const,
  student: (schoolId: string, studentId: string) => ["school", schoolId, "points", "student", studentId] as const,
  timeline: (schoolId: string, studentId: string, section: "SCHOOL" | "HOME") =>
    ["school", schoolId, "points", "timeline", studentId, section] as const,
  homeCategories: (schoolId: string, studentId: string) =>
    ["school", schoolId, "points", "home-categories", studentId] as const,
  badges: (schoolId: string) => ["school", schoolId, "points", "badges"] as const,
  report: (schoolId: string, filters: ReportFilters) => ["school", schoolId, "points", "report", reportQuery(filters)] as const,
  templates: (schoolId: string, classId: string | null) =>
    ["school", schoolId, "points", "templates", classId ?? "all"] as const,
  allTemplates: (schoolId: string) => ["school", schoolId, "points", "templates"] as const,
};

export const fetchCategories = async (schoolId: string, classId: string | null, opts: { includeInactive?: boolean; context?: "CLASS" | "SCHOOL" | "HOME" } = {}) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/point-categories", {
      params: {
        path: { schoolId },
        query: {
          ...(classId ? { classId } : {}),
          ...(opts.context ? { context: opts.context } : {}),
          ...(opts.includeInactive ? { includeInactive: true } : {}),
        },
      },
    }),
  );

export const fetchClassPoints = async (schoolId: string, classId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/points", { params: { path: { schoolId, classId } } }));

export const fetchLeaderboard = async (schoolId: string, classId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/leaderboard", { params: { path: { schoolId, classId } } }),
  );

export const fetchStudentPoints = async (schoolId: string, studentId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/points", { params: { path: { schoolId, studentId } } }),
  );

export const TIMELINE_PAGE = 20;

export const fetchTimeline = async (schoolId: string, studentId: string, section: "SCHOOL" | "HOME", cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/points/transactions", {
      params: { path: { schoolId, studentId }, query: { section, cursor, limit: TIMELINE_PAGE } },
    }),
  );

export const fetchHomeCategories = async (schoolId: string, studentId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/students/{studentId}/home-point-categories", {
      params: { path: { schoolId, studentId } },
    }),
  );

export const fetchBadges = async (schoolId: string, includeInactive = false) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/badges", {
      params: { path: { schoolId }, query: includeInactive ? { includeInactive } : {} },
    }),
  );

export const REPORT_PAGE = 50;

export const fetchReport = async (schoolId: string, filters: ReportFilters, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/points/report", {
      params: { path: { schoolId }, query: { ...reportQuery(filters), cursor, limit: REPORT_PAGE } },
    }),
  );

export const fetchTemplates = async (schoolId: string, classId: string | null, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/activity-templates", {
      params: { path: { schoolId }, query: { ...(classId ? { classId } : {}), cursor, limit: 50 } },
    }),
  );

/** The award call: one `clientOpId` per user action (see `award-op.ts`). A repeat of it answers 200 `replayed`. */
export async function postAward(schoolId: string, classId: string, body: AwardBody): Promise<AwardResult> {
  return unwrap(
    await api().POST("/api/v1/schools/{schoolId}/classes/{classId}/points/awards", {
      params: { path: { schoolId, classId } },
      body,
    }),
  );
}

export async function postHomeAward(schoolId: string, studentId: string, body: AwardBody): Promise<AwardResult> {
  return unwrap(
    await api().POST("/api/v1/schools/{schoolId}/students/{studentId}/home-points", {
      params: { path: { schoolId, studentId } },
      body,
    }),
  );
}

export async function reverseBatch(schoolId: string, batchId: string): Promise<AwardResult> {
  return unwrap(
    await api().POST("/api/v1/schools/{schoolId}/points/batches/{batchId}/reverse", {
      params: { path: { schoolId, batchId } },
      body: {},
    }),
  );
}

export async function awardBadge(schoolId: string, studentId: string, badgeId: string): Promise<void> {
  ensureOk(
    await api().POST("/api/v1/schools/{schoolId}/students/{studentId}/badges", {
      params: { path: { schoolId, studentId } },
      body: { badgeId },
    }),
  );
}

/**
 * Downloads the report as CSV through the BFF (never a link with a token). `no-store`: the file is per user and
 * audited, so no cache may keep or replay it. Throws `ApiRequestError` with the API's problem on failure
 * (403 no access, 429 `points.export_rate_limited`).
 */
export async function downloadReportCsv(schoolId: string, filters: ReportFilters): Promise<Blob> {
  const params = new URLSearchParams(reportQuery(filters) as Record<string, string>);
  const query = params.size > 0 ? `?${params.toString()}` : "";
  const response = await fetch(
    `${window.location.origin}/api/bff/api/v1/schools/${encodeURIComponent(schoolId)}/points/report.csv${query}`,
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

/** Saves a Blob through a temporary link, then frees it. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
