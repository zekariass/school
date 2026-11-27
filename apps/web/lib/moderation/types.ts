import type { components } from "@school/api-client";

type S = components["schemas"];

export type ReportRequest = S["ModerationReportRequest"];
export type ReportReceipt = S["ModerationReportReceipt"];
export type ReasonCode = ReportRequest["reasonCode"];
export type TargetType = ReportRequest["targetType"];

export type CaseSummary = S["ModerationCaseSummary"];
export type CaseDetail = S["ModerationCaseDetail"];
export type CaseAction = S["ModerationActionView"];
export type CaseReport = S["ModerationReportEntry"];
export type ReviewContent = S["ModerationReviewContent"];
export type ReviewItem = S["ModerationReviewItem"];
export type DecisionRequest = S["ModerationDecisionRequest"];
export type Decision = DecisionRequest["decision"];
export type Priority = S["ModerationPriorityRequest"]["priority"];
export type EmergencyInfo = S["SafetyEmergencyInfo"];

export type IncidentSummary = S["SafeguardingIncidentSummary"];
export type IncidentDetail = S["SafeguardingIncidentDetail"];
export type IncidentNote = S["SafeguardingNoteView"];
export type IncidentOpenReason = S["SafeguardingIncidentOpenRequest"]["reason"];
export type ConcernRequest = S["SafeguardingConcernRequest"];
export type IncidentCategory = ConcernRequest["category"];
export type IncidentSeverity = ConcernRequest["severity"];
export type SafeguardingCapability = S["SafeguardingCapabilityView"];

/** Reasons a person may pick when reporting (the API's enum, in the order the dialog shows them). */
export const REPORT_REASONS = [
  "INAPPROPRIATE",
  "HARASSMENT",
  "SAFETY_CONCERN",
  "PRIVACY",
  "SPAM",
  "INACCURATE",
  "COPYRIGHT",
  "OTHER",
] as const satisfies readonly ReasonCode[];

/** What the web can report: the target types that have a `ReportTarget` on the API. */
export const REPORTABLE_TARGETS = ["POST", "POST_COMMENT", "STORY", "MESSAGE", "USER"] as const satisfies readonly TargetType[];
export type ReportableTarget = (typeof REPORTABLE_TARGETS)[number];

export const DECISIONS = ["NO_ACTION", "HIDE", "REMOVE", "WARN_USER", "SUSPEND_USER", "ESCALATE"] as const satisfies readonly Decision[];
export const PRIORITIES = ["LOW", "NORMAL", "HIGH", "CRITICAL"] as const satisfies readonly Priority[];
export const CASE_STATUSES = ["OPEN", "ASSIGNED", "RESOLVED", "ESCALATED", "ALL"] as const;
export type CaseStatusFilter = (typeof CASE_STATUSES)[number];

/** The case's target types the queue can filter by. */
export const QUEUE_TARGETS = ["POST", "POST_COMMENT", "STORY", "MESSAGE", "USER"] as const;

export const INCIDENT_OPEN_REASONS = ["TRIAGE", "INVESTIGATION", "FOLLOW_UP", "REFERRAL", "OVERSIGHT", "LEGAL_REQUEST"] as const satisfies readonly IncidentOpenReason[];
export const INCIDENT_CATEGORIES = ["CONDUCT", "ONLINE_SAFETY", "WELLBEING", "DATA_BREACH", "OTHER"] as const satisfies readonly IncidentCategory[];
export const INCIDENT_SEVERITIES = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const satisfies readonly IncidentSeverity[];
export const INCIDENT_STATUSES = ["OPEN", "INVESTIGATING", "ACTION_TAKEN", "REFERRED", "CLOSED"] as const;
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number];

/** Bounds the API checks again; they keep the buttons off until the text can pass. */
export const DETAILS_MAX = 1000;
export const REASON_MIN = 10;
export const REASON_MAX = 500;
export const NOTE_MAX = 8000;
export const REFERRED_TO_MAX = 500;
export const NARRATIVE_MIN = 10;
