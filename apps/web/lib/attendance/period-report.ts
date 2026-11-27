import { isIsoDate } from "./dates";

/** The per-period summary (M7d) and the staff summaries: filters to query strings and CSV file names. */

/** Filters of the per-period summary: the class, the roll call, the subject and school-local dates. */
export interface PeriodFilters {
  classId?: string;
  slot?: string;
  subjectId?: string;
  from?: string;
  to?: string;
}

/** Query parameters of the per-period summary and its CSV: empty and malformed filters are left out. */
export function periodQuery(filters: PeriodFilters): PeriodFilters {
  return {
    ...(filters.classId ? { classId: filters.classId } : {}),
    ...(filters.slot ? { slot: filters.slot } : {}),
    ...(filters.subjectId ? { subjectId: filters.subjectId } : {}),
    ...(isIsoDate(filters.from) ? { from: filters.from } : {}),
    ...(isIsoDate(filters.to) ? { to: filters.to } : {}),
  };
}

/** The URL query string of the CSV call ("" without filters). */
export function periodQueryString(filters: PeriodFilters): string {
  const params = new URLSearchParams(periodQuery(filters) as Record<string, string>);
  return params.size > 0 ? `?${params.toString()}` : "";
}

export function periodCsvFileName(filters: PeriodFilters): string {
  const parts = [filters.slot, [filters.from, filters.to].filter(Boolean).join("_")].filter(Boolean);
  return `attendance-period-report${parts.length ? `-${parts.join("-")}` : ""}.csv`;
}

/** "Waiting time" of a 429, in whole minutes and seconds for the message "try again in {minutes} min {seconds} s". */
export function retryWait(seconds: number): { minutes: number; seconds: number } {
  const total = Math.max(0, Math.ceil(seconds));
  return { minutes: Math.floor(total / 60), seconds: total % 60 };
}
