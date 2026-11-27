import { DETAILS_MAX, REPORT_REASONS, type ReasonCode, type ReportRequest, type ReportableTarget } from "./types";

/** `[...text].length`: characters as a person counts them, not UTF-16 units. */
export function charCount(text: string): number {
  return [...text].length;
}

/**
 * The reasons offered for a target. A person or a message cannot be "copyright" or "inaccurate"; the other
 * reasons apply everywhere. The API accepts the whole enum and decides again.
 */
export function reasonsFor(target: ReportableTarget): readonly ReasonCode[] {
  if (target === "USER" || target === "MESSAGE") return REPORT_REASONS.filter((r) => r !== "COPYRIGHT" && r !== "INACCURATE");
  return REPORT_REASONS;
}

export interface ReportDraft {
  reason: ReasonCode | null;
  details: string;
}

export type ReportCheck = { ok: true; request: ReportRequest } | { ok: false; problem: "reasonMissing" | "detailsLong" };

/** Validates what the form holds and builds the request. Empty details are left out (the API treats them as none). */
export function checkReport(target: { type: ReportableTarget; id: string }, draft: ReportDraft): ReportCheck {
  if (!draft.reason) return { ok: false, problem: "reasonMissing" };
  const details = draft.details.trim();
  if (charCount(details) > DETAILS_MAX) return { ok: false, problem: "detailsLong" };
  return {
    ok: true,
    request: { targetType: target.type, targetId: target.id, reasonCode: draft.reason, ...(details ? { details } : {}) },
  };
}

/** Characters left under the details box (negative when over). */
export function detailsRemaining(details: string): number {
  return DETAILS_MAX - charCount(details.trim());
}

/** True when the emergency information is put in front of the reporter: somebody may be unsafe. */
export function showsEmergencyInfo(reason: ReasonCode | null): boolean {
  return reason === "SAFETY_CONCERN";
}
