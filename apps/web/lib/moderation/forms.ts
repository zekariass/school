import { charCount } from "./report";
import {
  NARRATIVE_MIN,
  NOTE_MAX,
  REASON_MAX,
  REASON_MIN,
  REFERRED_TO_MAX,
  type ConcernRequest,
  type Decision,
  type DecisionRequest,
  type IncidentCategory,
  type IncidentSeverity,
} from "./types";

export type TextCheck = { ok: true; text: string } | { ok: false; problem: "short" | "long" };

/** A text of `min` to `max` characters after trimming. The API checks again; this keeps the button off until it can pass. */
export function checkText(input: string, min: number, max: number): TextCheck {
  const text = input.trim();
  const length = charCount(text);
  if (length < min) return { ok: false, problem: "short" };
  if (length > max) return { ok: false, problem: "long" };
  return { ok: true, text };
}

/** The reason of a decision or a reversal: 10 to 500 characters. */
export const checkReason = (input: string) => checkText(input, REASON_MIN, REASON_MAX);

export function reasonRemaining(input: string): number {
  return REASON_MAX - charCount(input.trim());
}

export interface DecisionDraft {
  decision: Decision | null;
  reason: string;
  /** SUSPEND_USER only. */
  level: "SCHOOL" | "PLATFORM";
  /** ESCALATE only. */
  category: IncidentCategory;
  severity: IncidentSeverity;
}

export const EMPTY_DECISION: DecisionDraft = { decision: null, reason: "", level: "SCHOOL", category: "CONDUCT", severity: "HIGH" };

export type DecisionCheck = { ok: true; request: DecisionRequest } | { ok: false; problem: "decisionMissing" | "short" | "long" };

/**
 * Builds the request of a decision. Only the fields that belong to the decision are sent (the level of a
 * suspension, the category and severity of an escalation), and the reason is never anything but what was typed.
 */
export function checkDecision(draft: DecisionDraft, allowed: readonly Decision[], platformLock: boolean): DecisionCheck {
  if (!draft.decision || !allowed.includes(draft.decision)) return { ok: false, problem: "decisionMissing" };
  const reason = checkReason(draft.reason);
  if (!reason.ok) return reason;
  const request: DecisionRequest = { decision: draft.decision, reason: reason.text };
  if (draft.decision === "SUSPEND_USER") request.level = draft.level === "PLATFORM" && platformLock ? "PLATFORM" : "SCHOOL";
  if (draft.decision === "ESCALATE") {
    request.category = draft.category;
    request.severity = draft.severity;
  }
  return { ok: true, request };
}

export interface ConcernDraft {
  category: IncidentCategory | null;
  severity: IncidentSeverity | null;
  narrative: string;
}

export type ConcernCheck = { ok: true; request: ConcernRequest } | { ok: false; problem: "categoryMissing" | "severityMissing" | "short" | "long" };

export function checkConcern(draft: ConcernDraft): ConcernCheck {
  if (!draft.category) return { ok: false, problem: "categoryMissing" };
  if (!draft.severity) return { ok: false, problem: "severityMissing" };
  const narrative = checkText(draft.narrative, NARRATIVE_MIN, NOTE_MAX);
  if (!narrative.ok) return narrative;
  return { ok: true, request: { category: draft.category, severity: draft.severity, narrative: narrative.text } };
}

/** A note on an incident: 1 to 8,000 characters. */
export const checkNote = (input: string) => checkText(input, 1, NOTE_MAX);

/** The "referred to" text of a referral: 1 to 500 characters. */
export const checkReferredTo = (input: string) => checkText(input, 1, REFERRED_TO_MAX);

/** A note that is optional (empty is fine) but bounded. */
export function checkOptionalNote(input: string): { ok: true; text: string | undefined } | { ok: false; problem: "long" } {
  const text = input.trim();
  if (charCount(text) > NOTE_MAX) return { ok: false, problem: "long" };
  return { ok: true, text: text || undefined };
}
