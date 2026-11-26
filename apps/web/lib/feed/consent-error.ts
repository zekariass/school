import { ApiRequestError } from "@/lib/api-error";

/** A student named by `feed.media_consent_missing`; the name is present only when the caller may see the roster. */
export interface MissingConsentStudent {
  studentId: string;
  displayName?: string;
}

/** The two refusals of the consent gate (R-09), each with the API's own localized explanation. */
export type GateError =
  | { kind: "consent_missing"; students: MissingConsentStudent[]; detail?: string }
  | { kind: "confirmation_required"; detail?: string };

export const CONSENT_MISSING = "feed.media_consent_missing";
export const CONFIRMATION_REQUIRED = "feed.media_confirmation_required";

/** Reads the consent-gate problem out of a failed call; null for every other error. */
export function parseGateError(error: unknown): GateError | null {
  if (!(error instanceof ApiRequestError) || !error.problem) return null;
  const problem = error.problem as { code?: string; detail?: string; students?: unknown };
  if (problem.code === CONFIRMATION_REQUIRED) return { kind: "confirmation_required", detail: problem.detail };
  if (problem.code !== CONSENT_MISSING) return null;
  const students: MissingConsentStudent[] = [];
  if (Array.isArray(problem.students)) {
    for (const entry of problem.students) {
      if (typeof entry !== "object" || entry === null) continue;
      const { studentId, displayName } = entry as { studentId?: unknown; displayName?: unknown };
      if (typeof studentId !== "string") continue;
      students.push(typeof displayName === "string" && displayName ? { studentId, displayName } : { studentId });
    }
  }
  return { kind: "consent_missing", students, detail: problem.detail };
}

/** The ids of the students the author should untag (or ask the school about). */
export function missingStudentIds(error: GateError): string[] {
  return error.kind === "consent_missing" ? error.students.map((s) => s.studentId) : [];
}
