/**
 * Pure view logic of the guardian screens ("My children" and consent). The API decides what a
 * guardian may see; this only shapes it for display.
 */

export interface ConsentItemLike {
  consentType: "ACCOUNT_PARTICIPATION" | "MEDIA_PUBLICATION" | "TERMS" | "PRIVACY";
  needsDecision: boolean;
  outdated: boolean;
  myDecision?: { decision: "GRANTED" | "DENIED" | "WITHDRAWN" } | null;
  currentVersion?: { versionId: string } | null;
}

export type DecisionState = "GRANTED" | "DENIED" | "WITHDRAWN" | "NONE";

/** My latest decision on the current text; an outdated grant counts as none (the text changed). */
export function decisionState(item: Pick<ConsentItemLike, "myDecision" | "outdated">): DecisionState {
  const decision = item.myDecision?.decision;
  if (!decision) return "NONE";
  if (decision === "GRANTED" && item.outdated) return "NONE";
  return decision;
}

export type ConsentAction = "GRANTED" | "DENIED" | "WITHDRAWN";

/**
 * What a guardian can do next. Nothing to decide on without a published text. A grant can be
 * withdrawn; a refusal or withdrawal can be turned into a grant; with no decision (or an outdated
 * grant) both answers are offered.
 */
export function availableActions(item: ConsentItemLike): ConsentAction[] {
  if (!item.currentVersion) return [];
  switch (decisionState(item)) {
    case "GRANTED":
      return ["WITHDRAWN"];
    case "DENIED":
    case "WITHDRAWN":
      return ["GRANTED"];
    case "NONE":
      return ["GRANTED", "DENIED"];
  }
}

export interface ChildConsentsLike {
  items: readonly ConsentItemLike[];
}

/** Decisions still needed across the children (the adult's own agreements are counted apart). */
export function openDecisionCount(children: readonly ChildConsentsLike[]): number {
  return children.reduce((sum, c) => sum + c.items.filter((i) => i.needsDecision && !!i.currentVersion).length, 0);
}

export interface ClassLike {
  classId: string;
  name: string;
  teachers: readonly { teacherId: string; displayName: string }[];
}

export interface SchoolLike {
  schoolId: string;
  schoolName: string;
  classes: readonly ClassLike[];
}

/** Teacher names of a school's classes, once each, in the order they appear. */
export function teacherNames(school: SchoolLike): string[] {
  const seen = new Set<string>();
  const names: string[] = [];
  for (const c of school.classes) {
    for (const t of c.teachers) {
      if (!seen.has(t.teacherId)) {
        seen.add(t.teacherId);
        names.push(t.displayName);
      }
    }
  }
  return names;
}

/** True when the API answered a consent decision with the stale-version conflict. */
export function isVersionOutdatedProblem(problem: { code?: string } | null | undefined, status?: number): boolean {
  return problem?.code === "consent.version_outdated" || (status === 409 && problem?.code === undefined);
}

/**
 * True when the country has no consent or policy text (D42): 404 on a policy read, 409 on a decision. Never a
 * reason to show another country's document or to treat it as "no version yet": the screen shows the API's message.
 */
export function isCountryNotAvailableProblem(problem: { code?: string } | null | undefined): boolean {
  return problem?.code === "policy.country_not_available";
}

/** True when the child can no longer be decided for (the link ended or was restricted meanwhile). */
export function isChildGoneProblem(problem: { code?: string } | null | undefined): boolean {
  return problem?.code === "consent.child_not_found";
}

/** Consent items worth showing for a child: the two the child's school asks, in a stable order. */
export function orderedChildItems<T extends ConsentItemLike>(items: readonly T[]): T[] {
  const order = ["ACCOUNT_PARTICIPATION", "MEDIA_PUBLICATION"];
  return items
    .filter((i) => order.includes(i.consentType))
    .sort((a, b) => order.indexOf(a.consentType) - order.indexOf(b.consentType));
}

/** The plain-language line under a media decision: what it means for the child's photos. */
export function mediaOutcome(status: "GRANTED" | "DENIED" | "UNKNOWN" | undefined): "allowed" | "blocked" {
  return status === "GRANTED" ? "allowed" : "blocked";
}
