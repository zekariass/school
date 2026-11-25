import { daysBetween, isIsoDate } from "./dates";

/** The attendance summary report and the daily dashboard: filters to query parameters, range checks, file names. */

export interface ReportFilters {
  classId?: string;
  studentId?: string;
  /** School-local ISO date `yyyy-mm-dd`, inclusive. Empty: the API's default (the last 30 days). */
  from?: string;
  to?: string;
}

/** The API refuses a range whose end is more than this many days after its start. */
export const MAX_REPORT_DAYS = 400;

/** True when the range is open-ended or ordered, not longer than the API allows, and made of real dates. */
export function isValidRange(from: string | undefined, to: string | undefined): boolean {
  if (from && !isIsoDate(from)) return false;
  if (to && !isIsoDate(to)) return false;
  if (from && to) {
    if (from > to) return false;
    if (daysBetween(from, to) > MAX_REPORT_DAYS) return false; // the API's own check
  }
  return true;
}

/** Query parameters of the report and its CSV: empty filters are left out so the API sees only what is chosen. */
export function reportQuery(filters: ReportFilters): ReportFilters {
  return {
    ...(filters.classId ? { classId: filters.classId } : {}),
    ...(filters.studentId ? { studentId: filters.studentId } : {}),
    ...(isIsoDate(filters.from) ? { from: filters.from } : {}),
    ...(isIsoDate(filters.to) ? { to: filters.to } : {}),
  };
}

/** Same filters as the report: the URL query string of the CSV call ("" without filters). */
export function reportQueryString(filters: ReportFilters): string {
  const params = new URLSearchParams(reportQuery(filters) as Record<string, string>);
  return params.size > 0 ? `?${params.toString()}` : "";
}

export function csvFileName(filters: ReportFilters): string {
  const range = [filters.from, filters.to].filter(Boolean).join("_");
  return `attendance-report${range ? `-${range}` : ""}.csv`;
}

/** What a dashboard row says about its class: a roll exists, none yet, or there is nobody to call. */
export type DashboardState = "taken" | "notTaken" | "empty";

export function dashboardState(row: { taken?: boolean; students?: number }): DashboardState {
  if ((row.students ?? 0) === 0) return "empty";
  return row.taken ? "taken" : "notTaken";
}

/** The rows to show: all of them, or only the classes whose roll has not been taken yet. */
export function visibleDashboardRows<T extends { taken?: boolean; students?: number }>(rows: readonly T[], onlyNotTaken: boolean): T[] {
  return onlyNotTaken ? rows.filter((r) => dashboardState(r) === "notTaken") : [...rows];
}

/** Query of the dashboard: the school-local date (omitted means today there) and the slot (omitted: the school's first). */
export function dashboardQuery(date: string | null, slot: string | null): { date?: string; slot?: string } {
  return { ...(date && isIsoDate(date) ? { date } : {}), ...(slot ? { slot } : {}) };
}

/** Sum of the numeric counters of report rows. */
export function sumReport(rows: readonly { marked?: number; absent?: number; late?: number; present?: number; excused?: number; leftEarly?: number }[]) {
  const total = { marked: 0, present: 0, absent: 0, late: 0, excused: 0, leftEarly: 0 };
  for (const r of rows) {
    total.marked += r.marked ?? 0;
    total.present += r.present ?? 0;
    total.absent += r.absent ?? 0;
    total.late += r.late ?? 0;
    total.excused += r.excused ?? 0;
    total.leftEarly += r.leftEarly ?? 0;
  }
  return total;
}
