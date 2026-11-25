/** The points report: filters to query parameters, range checks and the CSV file name. */

export interface ReportFilters {
  classId?: string;
  categoryId?: string;
  /** ISO date `yyyy-mm-dd`, inclusive. */
  from?: string;
  to?: string;
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function isIsoDate(value: string | undefined): value is string {
  if (!value || !ISO_DATE.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  // A round trip catches "2026-02-30", which Date would roll over to March.
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

/** True when the range is open-ended or ordered (`from` not after `to`); malformed dates are not valid. */
export function isValidRange(from: string | undefined, to: string | undefined): boolean {
  if (from && !isIsoDate(from)) return false;
  if (to && !isIsoDate(to)) return false;
  return !from || !to || from <= to;
}

/** Query parameters for the report and its CSV: empty filters are left out so the API sees only what is chosen. */
export function reportQuery(filters: ReportFilters): ReportFilters {
  return {
    ...(filters.classId ? { classId: filters.classId } : {}),
    ...(filters.categoryId ? { categoryId: filters.categoryId } : {}),
    ...(isIsoDate(filters.from) ? { from: filters.from } : {}),
    ...(isIsoDate(filters.to) ? { to: filters.to } : {}),
  };
}

export function csvFileName(filters: ReportFilters): string {
  const range = [filters.from, filters.to].filter(Boolean).join("_");
  return `points-report${range ? `-${range}` : ""}.csv`;
}

/** Net points of the rows on screen. */
export const sumPoints = (rows: readonly { points?: number }[]): number => rows.reduce((n, r) => n + (r.points ?? 0), 0);
