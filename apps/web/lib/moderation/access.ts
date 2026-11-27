import type { CaseAction, CaseDetail, Decision, IncidentStatus, IncidentSummary } from "./types";

/**
 * What a person may do in the moderation and safeguarding screens. Everything here only decides which buttons to
 * show, from the permissions `/me` lists and the shape of what the API sent: the API stays the authority (it answers
 * 403, 404 or 409 and the screens show that inline), and nothing is ever filtered on the client for secrecy.
 */
export const MOD_PERM = {
  schoolModeration: "school.moderation.manage",
  safeguardingRead: "school.safeguarding.read",
  safeguardingManage: "school.safeguarding.manage",
  platformModeration: "platform.moderation.manage",
  platformUsersLock: "platform.users.lock",
} as const;

export interface Viewer {
  userId: string;
  /** Permission codes that apply where the screen is (the school's, or any held at platform level). */
  permissions: ReadonlySet<string>;
}

/** The school queue page: holders of `school.moderation.manage` (school administrators and the leads). */
export function canWorkSchoolQueue(permissions: ReadonlySet<string>): boolean {
  return permissions.has(MOD_PERM.schoolModeration);
}

/** The platform queue page. */
export function canWorkPlatformQueue(permissions: ReadonlySet<string>): boolean {
  return permissions.has(MOD_PERM.platformModeration);
}

/**
 * The safeguarding area: exactly `school.safeguarding.read` (the two lead roles). Administrators do not hold it and
 * are not offered the page; if they type the address anyway the page answers like an unknown page.
 */
export function canOpenSafeguarding(permissions: ReadonlySet<string>): boolean {
  return permissions.has(MOD_PERM.safeguardingRead);
}

/** True for a lead: somebody who reads reporter identities, sets priorities and the legal hold. */
export function isLead(permissions: ReadonlySet<string>): boolean {
  return permissions.has(MOD_PERM.safeguardingRead);
}

export const OPEN_STATUSES: readonly string[] = ["OPEN", "ASSIGNED"];

export function isOpenCase(status: string | undefined): boolean {
  return !!status && OPEN_STATUSES.includes(status);
}

/** Content a decision can act on: the owner can hide or remove it. A person (USER) can only be warned, suspended or escalated. */
const CONTENT_TARGETS: readonly string[] = ["POST", "POST_COMMENT", "STORY", "MESSAGE"];

export function isContentTarget(targetType: string | undefined): boolean {
  return !!targetType && CONTENT_TARGETS.includes(targetType);
}

const byTime = (a: CaseAction, b: CaseAction) => (a.createdAt ?? "").localeCompare(b.createdAt ?? "");

/** The latest history row among `codes` (the history is append-only; the API sends it oldest first, sorted again here). */
export function lastActionOf(actions: readonly CaseAction[] | undefined, codes: readonly string[]): CaseAction | undefined {
  return [...(actions ?? [])].filter((a) => !!a.action && codes.includes(a.action)).sort(byTime).at(-1);
}

export type SuspensionState = { held: false } | { held: true; level: "SCHOOL" | "PLATFORM" };

/** Is a suspension in force that this case decided (and can be lifted)? */
export function suspensionOf(actions: readonly CaseAction[] | undefined): SuspensionState {
  const last = lastActionOf(actions, ["SUSPEND_USER", "LIFT_SUSPENSION", "UNLOCK_USER"]);
  if (last?.action !== "SUSPEND_USER") return { held: false };
  return { held: true, level: last.level === "PLATFORM" ? "PLATFORM" : "SCHOOL" };
}

/** True when the latest hide/remove/restore of this case is a HIDE: only that can be undone (a removal cannot). */
export function canUndoHide(actions: readonly CaseAction[] | undefined): boolean {
  return lastActionOf(actions, ["HIDE", "REMOVE", "RESTORE"])?.action === "HIDE";
}

export interface CaseControls {
  /** "Take this case": it is open and not already the viewer's. */
  assignToMe: boolean;
  decisions: Decision[];
  /** Level PLATFORM is offered for SUSPEND_USER only to holders of `platform.users.lock`. */
  platformLock: boolean;
  reopen: boolean;
  restore: boolean;
  liftSuspension: boolean;
  referUp: boolean;
  setPriority: boolean;
  legalHold: boolean;
}

/**
 * The buttons of a case page. `detail` is what the API sent: its status, queue, history and subject decide which
 * decisions make sense (the API refuses the rest with `moderation.decision_not_applicable`).
 */
export function caseControls(detail: CaseDetail, viewer: Viewer): CaseControls {
  const summary = detail.summary;
  const open = isOpenCase(summary?.status);
  const lead = isLead(viewer.permissions);
  const lock = viewer.permissions.has(MOD_PERM.platformUsersLock);
  const decisions: Decision[] = [];
  if (open) {
    decisions.push("NO_ACTION");
    if (isContentTarget(summary?.targetType)) decisions.push("HIDE", "REMOVE");
    if (detail.subjectUserId) decisions.push("WARN_USER", "SUSPEND_USER");
    decisions.push("ESCALATE");
  }
  const suspension = suspensionOf(detail.actions);
  return {
    assignToMe: open && summary?.assignedTo !== viewer.userId,
    decisions,
    platformLock: lock,
    reopen: summary?.status === "RESOLVED" || (summary?.status === "ESCALATED" && lead),
    restore: canUndoHide(detail.actions),
    liftSuspension: suspension.held && (suspension.level === "SCHOOL" || lock),
    referUp: open && summary?.queue === "SCHOOL",
    setPriority: lead,
    legalHold: lead || viewer.permissions.has(MOD_PERM.platformModeration),
  };
}

/** Which incident transitions are offered (the API's workflow; reopening goes to INVESTIGATING, never back to OPEN). */
export function nextIncidentStatuses(from: string | undefined): IncidentStatus[] {
  switch (from) {
    case "OPEN":
      return ["INVESTIGATING", "CLOSED"];
    case "INVESTIGATING":
      return ["ACTION_TAKEN", "REFERRED", "CLOSED"];
    case "ACTION_TAKEN":
      return ["REFERRED", "INVESTIGATING", "CLOSED"];
    case "REFERRED":
      return ["ACTION_TAKEN", "INVESTIGATING", "CLOSED"];
    case "CLOSED":
      return ["INVESTIGATING"];
    default:
      return [];
  }
}

/** Closing an incident and re-opening a closed one both need a note of at least 10 characters. */
export function statusNeedsNote(from: string | undefined, to: string): boolean {
  return to === "CLOSED" || from === "CLOSED";
}

/** "Assign to me" is offered while the incident is not closed and is not already mine. */
export function canAssignIncidentToMe(incident: Pick<IncidentSummary, "status" | "assignedTo">, viewer: Pick<Viewer, "userId">): boolean {
  return incident.status !== "CLOSED" && incident.assignedTo !== viewer.userId;
}
