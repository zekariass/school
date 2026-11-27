import { describe, expect, it } from "vitest";
import {
  availableActions,
  decisionState,
  isChildGoneProblem,
  isCountryNotAvailableProblem,
  isVersionOutdatedProblem,
mediaOutcome,
  openDecisionCount,
  orderedChildItems,
  teacherNames,
  type ConsentItemLike,
} from "./family";

const item = (over: Partial<ConsentItemLike> = {}): ConsentItemLike => ({
  consentType: "MEDIA_PUBLICATION",
  needsDecision: true,
  outdated: false,
  myDecision: null,
  currentVersion: { versionId: "v1" },
  ...over,
});

describe("decisionState", () => {
  it("is NONE without a decision", () => {
    expect(decisionState(item())).toBe("NONE");
  });

  it("reports the latest decision", () => {
    expect(decisionState(item({ myDecision: { decision: "DENIED" } }))).toBe("DENIED");
    expect(decisionState(item({ myDecision: { decision: "WITHDRAWN" } }))).toBe("WITHDRAWN");
    expect(decisionState(item({ myDecision: { decision: "GRANTED" } }))).toBe("GRANTED");
  });

  it("does not count a grant on a text that has since changed", () => {
    expect(decisionState(item({ myDecision: { decision: "GRANTED" }, outdated: true }))).toBe("NONE");
  });
});

describe("availableActions", () => {
  it("offers both answers when nothing is decided", () => {
    expect(availableActions(item())).toEqual(["GRANTED", "DENIED"]);
  });

  it("lets a grant be withdrawn and a refusal be turned into a grant", () => {
    expect(availableActions(item({ myDecision: { decision: "GRANTED" }, needsDecision: false }))).toEqual(["WITHDRAWN"]);
    expect(availableActions(item({ myDecision: { decision: "DENIED" }, needsDecision: false }))).toEqual(["GRANTED"]);
    expect(availableActions(item({ myDecision: { decision: "WITHDRAWN" }, needsDecision: false }))).toEqual(["GRANTED"]);
  });

  it("asks again after the text changed", () => {
    expect(availableActions(item({ myDecision: { decision: "GRANTED" }, outdated: true }))).toEqual(["GRANTED", "DENIED"]);
  });

  it("offers nothing without a published text", () => {
    expect(availableActions(item({ currentVersion: null }))).toEqual([]);
    expect(availableActions(item({ currentVersion: undefined }))).toEqual([]);
  });
});

describe("openDecisionCount", () => {
  it("counts the open decisions that have a text to decide on", () => {
    const children = [
      { items: [item(), item({ needsDecision: false }), item({ currentVersion: null })] },
      { items: [item({ consentType: "ACCOUNT_PARTICIPATION" })] },
    ];
    expect(openDecisionCount(children)).toBe(2);
    expect(openDecisionCount([])).toBe(0);
  });
});

describe("teacherNames", () => {
  it("lists each teacher once", () => {
    const school = {
      schoolId: "s",
      schoolName: "Sunrise",
      classes: [
        { classId: "c1", name: "3A", teachers: [{ teacherId: "t1", displayName: "Almaz" }, { teacherId: "t2", displayName: "Dawit" }] },
        { classId: "c2", name: "Music", teachers: [{ teacherId: "t1", displayName: "Almaz" }] },
      ],
    };
    expect(teacherNames(school)).toEqual(["Almaz", "Dawit"]);
  });
});

describe("problems", () => {
  it("recognizes a stale policy version", () => {
    expect(isVersionOutdatedProblem({ code: "consent.version_outdated" }, 409)).toBe(true);
    expect(isVersionOutdatedProblem({ code: "consent.child_not_found" }, 404)).toBe(false);
    expect(isVersionOutdatedProblem(null, 409)).toBe(true);
    expect(isVersionOutdatedProblem(null, 500)).toBe(false);
    // a country without text is its own problem (409 on a decision): never read as a stale version
    expect(isVersionOutdatedProblem({ code: "policy.country_not_available" }, 409)).toBe(false);
    expect(isCountryNotAvailableProblem({ code: "policy.country_not_available" })).toBe(true);
    expect(isCountryNotAvailableProblem({ code: "policy.not_found" })).toBe(false);
    expect(isCountryNotAvailableProblem(undefined)).toBe(false);
  });

  it("recognizes a child that can no longer be decided for", () => {
    expect(isChildGoneProblem({ code: "consent.child_not_found" })).toBe(true);
    expect(isChildGoneProblem({ code: "other" })).toBe(false);
    expect(isChildGoneProblem(undefined)).toBe(false);
  });
});

describe("orderedChildItems", () => {
  it("keeps the two child consents in a stable order", () => {
    const items = [item({ consentType: "MEDIA_PUBLICATION" }), item({ consentType: "TERMS" }), item({ consentType: "ACCOUNT_PARTICIPATION" })];
    expect(orderedChildItems(items).map((i) => i.consentType)).toEqual(["ACCOUNT_PARTICIPATION", "MEDIA_PUBLICATION"]);
  });
});

describe("mediaOutcome", () => {
  it("blocks media unless granted", () => {
    expect(mediaOutcome("GRANTED")).toBe("allowed");
    expect(mediaOutcome("UNKNOWN")).toBe("blocked");
    expect(mediaOutcome("DENIED")).toBe("blocked");
    expect(mediaOutcome(undefined)).toBe("blocked");
  });
});
