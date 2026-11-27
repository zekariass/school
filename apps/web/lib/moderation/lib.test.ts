import { describe, expect, it } from "vitest";
import { ApiRequestError } from "@/lib/api-error";
import {
  MOD_PERM,
  canAssignIncidentToMe,
  canOpenSafeguarding,
  canUndoHide,
  caseControls,
  isContentTarget,
  nextIncidentStatuses,
  statusNeedsNote,
  suspensionOf,
  type Viewer,
} from "./access";
import { EMPTY_DECISION, checkConcern, checkDecision, checkNote, checkOptionalNote, checkReason, checkReferredTo } from "./forms";
import { isNothingHere, outcomeOf } from "./outcome";
import { charCount, checkReport, detailsRemaining, reasonsFor, showsEmergencyInfo } from "./report";
import type { CaseAction, CaseDetail } from "./types";
import { DETAILS_MAX } from "./types";
import { actionKey, isUrgentPriority, itemTypeKey, knownOr, reasonCounts, reasonKey, roleKey } from "./view";

const ME = "u-me";
const viewer = (permissions: string[], userId = ME): Viewer => ({ userId, permissions: new Set(permissions) });
const MODERATOR = viewer([MOD_PERM.schoolModeration]);
const LEAD = viewer([MOD_PERM.schoolModeration, MOD_PERM.safeguardingRead, MOD_PERM.safeguardingManage]);
const PLATFORM = viewer([MOD_PERM.platformModeration, MOD_PERM.platformUsersLock]);

const action = (a: string, createdAt: string, level?: string): CaseAction => ({ id: a + createdAt, action: a, createdAt, ...(level ? { level } : {}) });
const detail = (over: { status?: string; targetType?: string; queue?: string; assignedTo?: string; subject?: string | null; actions?: CaseAction[] } = {}): CaseDetail => ({
  summary: { id: "c1", status: over.status ?? "OPEN", targetType: over.targetType ?? "POST", queue: over.queue ?? "SCHOOL", assignedTo: over.assignedTo, priority: "NORMAL", legalHold: false } as never,
  subjectUserId: over.subject === null ? undefined : (over.subject ?? "u-subject"),
  reports: [],
  actions: over.actions ?? [],
  reporterIdentityVisible: false,
});

describe("report form checks", () => {
  const target = { type: "POST" as const, id: "p1" };

  it("needs a reason and builds exactly the request: ids, the reason code and trimmed details", () => {
    expect(checkReport(target, { reason: null, details: "" })).toEqual({ ok: false, problem: "reasonMissing" });
    expect(checkReport(target, { reason: "SPAM", details: "" })).toEqual({ ok: true, request: { targetType: "POST", targetId: "p1", reasonCode: "SPAM" } });
    expect(checkReport(target, { reason: "SAFETY_CONCERN", details: "  it hurts  " })).toEqual({
      ok: true,
      request: { targetType: "POST", targetId: "p1", reasonCode: "SAFETY_CONCERN", details: "it hurts" },
    });
  });

  it("keeps details to 1,000 characters (counted as people count them)", () => {
    expect(checkReport(target, { reason: "OTHER", details: "a".repeat(DETAILS_MAX) }).ok).toBe(true);
    expect(checkReport(target, { reason: "OTHER", details: "a".repeat(DETAILS_MAX + 1) })).toEqual({ ok: false, problem: "detailsLong" });
    expect(charCount("😀😀")).toBe(2);
    expect(detailsRemaining("abc")).toBe(DETAILS_MAX - 3);
  });

  it("offers copyright and 'inaccurate' for content but not for a person or a message", () => {
    expect(reasonsFor("POST")).toContain("COPYRIGHT");
    expect(reasonsFor("STORY")).toContain("INACCURATE");
    expect(reasonsFor("USER")).not.toContain("COPYRIGHT");
    expect(reasonsFor("MESSAGE")).not.toContain("INACCURATE");
    expect(reasonsFor("MESSAGE")).toContain("SAFETY_CONCERN");
  });

  it("puts the emergency information in front of a reporter who says somebody may be unsafe", () => {
    expect(showsEmergencyInfo("SAFETY_CONCERN")).toBe(true);
    expect(showsEmergencyInfo("SPAM")).toBe(false);
    expect(showsEmergencyInfo(null)).toBe(false);
  });
});

describe("who sees which buttons on a case", () => {
  it("lets a school moderator take, decide and hand up an open school case, and nothing else", () => {
    const c = caseControls(detail(), MODERATOR);
    expect(c.assignToMe).toBe(true);
    expect(c.decisions).toEqual(["NO_ACTION", "HIDE", "REMOVE", "WARN_USER", "SUSPEND_USER", "ESCALATE"]);
    expect(c.referUp).toBe(true);
    expect(c.platformLock).toBe(false);
    expect(c.setPriority).toBe(false);
    expect(c.legalHold).toBe(false);
    expect(c.reopen || c.restore || c.liftSuspension).toBe(false);
  });

  it("does not offer 'take this case' for a case that is already the viewer's", () => {
    expect(caseControls(detail({ status: "ASSIGNED", assignedTo: ME }), MODERATOR).assignToMe).toBe(false);
    expect(caseControls(detail({ status: "ASSIGNED", assignedTo: "someone" }), MODERATOR).assignToMe).toBe(true);
  });

  it("gives a safeguarding lead the priority and the legal hold, and a platform moderator the hold and the platform lock", () => {
    expect(caseControls(detail(), LEAD)).toMatchObject({ setPriority: true, legalHold: true, platformLock: false });
    expect(caseControls(detail({ queue: "PLATFORM" }), PLATFORM)).toMatchObject({ setPriority: false, legalHold: true, platformLock: true, referUp: false });
  });

  it("offers hide and remove only for content, and warn and suspend only when the API named a person", () => {
    expect(caseControls(detail({ targetType: "USER" }), MODERATOR).decisions).toEqual(["NO_ACTION", "WARN_USER", "SUSPEND_USER", "ESCALATE"]);
    expect(caseControls(detail({ subject: null }), MODERATOR).decisions).toEqual(["NO_ACTION", "HIDE", "REMOVE", "ESCALATE"]);
    expect(isContentTarget("MESSAGE")).toBe(true);
    expect(isContentTarget("USER")).toBe(false);
  });

  it("offers decisions only while the case is open", () => {
    for (const status of ["RESOLVED", "ESCALATED"]) expect(caseControls(detail({ status }), MODERATOR).decisions).toEqual([]);
    expect(caseControls(detail({ status: "RESOLVED" }), MODERATOR).reopen).toBe(true);
  });

  it("lets only a lead reopen a case that was escalated to safeguarding", () => {
    expect(caseControls(detail({ status: "ESCALATED" }), MODERATOR).reopen).toBe(false);
    expect(caseControls(detail({ status: "ESCALATED" }), LEAD).reopen).toBe(true);
  });

  it("offers 'show it again' only after a hide (a removal cannot be undone from the case)", () => {
    const hide = [action("HIDE", "2026-11-01T10:00:00Z")];
    const remove = [action("REMOVE", "2026-11-01T10:00:00Z")];
    const hideRestore = [action("HIDE", "2026-11-01T10:00:00Z"), action("RESTORE", "2026-11-02T10:00:00Z")];
    const removeAfterHide = [action("HIDE", "2026-11-01T10:00:00Z"), action("REMOVE", "2026-11-02T10:00:00Z")];
    expect(canUndoHide(hide)).toBe(true);
    expect(canUndoHide(remove)).toBe(false);
    expect(canUndoHide(hideRestore)).toBe(false);
    expect(canUndoHide(removeAfterHide)).toBe(false);
    expect(canUndoHide([])).toBe(false);
    // The order of the array does not matter: the timestamps do.
    expect(canUndoHide([...hideRestore].reverse())).toBe(false);
  });

  it("offers lifting a school suspension to the queue and a platform lock only to holders of platform.users.lock", () => {
    const school = detail({ status: "RESOLVED", actions: [action("SUSPEND_USER", "2026-11-01T10:00:00Z", "SCHOOL")] });
    const platform = detail({ status: "RESOLVED", actions: [action("SUSPEND_USER", "2026-11-01T10:00:00Z", "PLATFORM")] });
    expect(suspensionOf(school.actions)).toEqual({ held: true, level: "SCHOOL" });
    expect(caseControls(school, MODERATOR).liftSuspension).toBe(true);
    expect(caseControls(platform, MODERATOR).liftSuspension).toBe(false);
    expect(caseControls(platform, PLATFORM).liftSuspension).toBe(true);
    const lifted = detail({ actions: [action("SUSPEND_USER", "2026-11-01T10:00:00Z", "SCHOOL"), action("LIFT_SUSPENSION", "2026-11-02T10:00:00Z", "SCHOOL")] });
    expect(suspensionOf(lifted.actions)).toEqual({ held: false });
    expect(caseControls(lifted, MODERATOR).liftSuspension).toBe(false);
  });
});

describe("safeguarding access and incident workflow", () => {
  it("opens the safeguarding area only for school.safeguarding.read (administrators and moderators do not hold it)", () => {
    expect(canOpenSafeguarding(new Set([MOD_PERM.safeguardingRead]))).toBe(true);
    expect(canOpenSafeguarding(new Set(["school.manage", MOD_PERM.schoolModeration, "school.staff.manage"]))).toBe(false);
    expect(canOpenSafeguarding(new Set())).toBe(false);
  });

  it("follows the API's status workflow: reopening goes to INVESTIGATING, closing and reopening need a note", () => {
    expect(nextIncidentStatuses("OPEN")).toEqual(["INVESTIGATING", "CLOSED"]);
    expect(nextIncidentStatuses("INVESTIGATING")).toEqual(["ACTION_TAKEN", "REFERRED", "CLOSED"]);
    expect(nextIncidentStatuses("CLOSED")).toEqual(["INVESTIGATING"]);
    expect(nextIncidentStatuses("OPEN")).not.toContain("OPEN");
    expect(nextIncidentStatuses("nonsense")).toEqual([]);
    expect(statusNeedsNote("INVESTIGATING", "CLOSED")).toBe(true);
    expect(statusNeedsNote("CLOSED", "INVESTIGATING")).toBe(true);
    expect(statusNeedsNote("OPEN", "INVESTIGATING")).toBe(false);
  });

  it("offers 'assign to me' until the incident is closed or already mine", () => {
    expect(canAssignIncidentToMe({ status: "OPEN" }, LEAD)).toBe(true);
    expect(canAssignIncidentToMe({ status: "OPEN", assignedTo: ME }, LEAD)).toBe(false);
    expect(canAssignIncidentToMe({ status: "CLOSED" }, LEAD)).toBe(false);
  });
});

describe("form checks", () => {
  it("keeps a reason to 10 to 500 characters", () => {
    expect(checkReason("too short")).toEqual({ ok: false, problem: "short" });
    expect(checkReason("  long enough  ")).toEqual({ ok: true, text: "long enough" });
    expect(checkReason("x".repeat(501))).toEqual({ ok: false, problem: "long" });
  });

  it("sends only the fields that belong to the decision", () => {
    const allowed = ["NO_ACTION", "HIDE", "SUSPEND_USER", "ESCALATE"] as const;
    const base = { ...EMPTY_DECISION, reason: "A reason that is long enough" };
    expect(checkDecision({ ...base, decision: null }, allowed, false)).toEqual({ ok: false, problem: "decisionMissing" });
    expect(checkDecision({ ...base, decision: "REMOVE" }, allowed, false)).toEqual({ ok: false, problem: "decisionMissing" });
    expect(checkDecision({ ...base, decision: "HIDE", level: "PLATFORM", severity: "CRITICAL" }, allowed, true)).toEqual({ ok: true, request: { decision: "HIDE", reason: "A reason that is long enough" } });
    expect(checkDecision({ ...base, decision: "SUSPEND_USER" }, allowed, false)).toEqual({ ok: true, request: { decision: "SUSPEND_USER", reason: "A reason that is long enough", level: "SCHOOL" } });
    // A platform lock is offered only to holders of platform.users.lock, whatever the form was made to say.
    expect(checkDecision({ ...base, decision: "SUSPEND_USER", level: "PLATFORM" }, allowed, false)).toMatchObject({ request: { level: "SCHOOL" } });
    expect(checkDecision({ ...base, decision: "SUSPEND_USER", level: "PLATFORM" }, allowed, true)).toMatchObject({ request: { level: "PLATFORM" } });
    expect(checkDecision({ ...base, decision: "ESCALATE", category: "WELLBEING", severity: "CRITICAL" }, allowed, false)).toEqual({
      ok: true,
      request: { decision: "ESCALATE", reason: "A reason that is long enough", category: "WELLBEING", severity: "CRITICAL" },
    });
    expect(checkDecision({ ...base, decision: "NO_ACTION", reason: "short" }, allowed, false)).toEqual({ ok: false, problem: "short" });
  });

  it("checks a concern: kind, seriousness and a narrative of 10 to 8,000 characters", () => {
    expect(checkConcern({ category: null, severity: "HIGH", narrative: "x".repeat(20) })).toEqual({ ok: false, problem: "categoryMissing" });
    expect(checkConcern({ category: "CONDUCT", severity: null, narrative: "x".repeat(20) })).toEqual({ ok: false, problem: "severityMissing" });
    expect(checkConcern({ category: "CONDUCT", severity: "HIGH", narrative: "short" })).toEqual({ ok: false, problem: "short" });
    expect(checkConcern({ category: "CONDUCT", severity: "HIGH", narrative: "x".repeat(8001) })).toEqual({ ok: false, problem: "long" });
    expect(checkConcern({ category: "OTHER", severity: "LOW", narrative: "  what I saw today  " })).toEqual({
      ok: true,
      request: { category: "OTHER", severity: "LOW", narrative: "what I saw today" },
    });
  });

  it("checks notes and referrals", () => {
    expect(checkNote("   ")).toEqual({ ok: false, problem: "short" });
    expect(checkNote("x".repeat(8001))).toEqual({ ok: false, problem: "long" });
    expect(checkReferredTo("")).toEqual({ ok: false, problem: "short" });
    expect(checkReferredTo("x".repeat(501))).toEqual({ ok: false, problem: "long" });
    expect(checkOptionalNote("  ")).toEqual({ ok: true, text: undefined });
    expect(checkOptionalNote("x".repeat(8001))).toEqual({ ok: false, problem: "long" });
  });
});

describe("outcomes of failed calls", () => {
  const err = (status: number, extra: { detail?: string; retryAfter?: number } = {}) =>
    new ApiRequestError(status, extra.detail ? ({ type: "x", title: "t", status, code: "c", detail: extra.detail } as never) : undefined, extra.retryAfter);

  it("treats a 404 as 'nothing here' and never as 'not allowed'", () => {
    expect(outcomeOf(err(404))).toEqual({ kind: "nothing" });
    expect(isNothingHere(err(404))).toBe(true);
    expect(isNothingHere(err(403))).toBe(false);
  });

  it("keeps the API's own sentence for the rest and the wait for a 429", () => {
    expect(outcomeOf(err(403, { detail: "No." }))).toEqual({ kind: "forbidden", detail: "No." });
    expect(outcomeOf(err(409, { detail: "Closed." }))).toEqual({ kind: "conflict", detail: "Closed." });
    expect(outcomeOf(err(429, { retryAfter: 30 }))).toMatchObject({ kind: "rateLimited", retryAfter: 30 });
    expect(outcomeOf(err(500))).toEqual({ kind: "failed", detail: undefined });
    expect(outcomeOf(new Error("x"))).toEqual({ kind: "failed" });
  });
});

describe("labels", () => {
  it("maps role codes and the old phrases, and falls back to 'a person'", () => {
    expect(roleKey("STUDENT")).toBe("STUDENT");
    expect(roleKey("a guardian")).toBe("GUARDIAN");
    expect(roleKey("A student")).toBe("STUDENT");
    expect(roleKey("somebody odd")).toBe("OTHER");
    expect(roleKey(null)).toBe("OTHER");
  });

  it("falls back instead of showing an unknown code", () => {
    expect(actionKey("HIDE")).toBe("HIDE");
    expect(actionKey("TELEPORT")).toBe("OTHER");
    expect(reasonKey("HARASSMENT")).toBe("HARASSMENT");
    expect(reasonKey("???")).toBe("OTHER");
    expect(itemTypeKey("COMMENT")).toBe("COMMENT");
    expect(itemTypeKey("x")).toBe("POST");
    expect(knownOr("zzz", ["A", "B"] as const, "A")).toBe("A");
    expect(isUrgentPriority("CRITICAL")).toBe(true);
    expect(isUrgentPriority("NORMAL")).toBe(false);
  });

  it("lists a case's reasons most-reported first", () => {
    expect(reasonCounts({ SPAM: 1, HARASSMENT: 3, OTHER: 1 })).toEqual([
      { code: "HARASSMENT", count: 3 },
      { code: "OTHER", count: 1 },
      { code: "SPAM", count: 1 },
    ]);
    expect(reasonCounts(undefined)).toEqual([]);
  });
});
