import { describe, expect, it } from "vitest";
import { buildReviewBody, hasAnyAction, itemActions, noteProblem, noteRequired } from "./actions";
import type { PortfolioItem } from "./queries";

const item = (status: PortfolioItem["status"], caps: Partial<PortfolioItem["capabilities"]>, patch: Partial<PortfolioItem> = {}): PortfolioItem =>
  ({ id: "i1", status, featured: false, visibility: "CLASS", capabilities: caps, ...patch }) as unknown as PortfolioItem;

describe("itemActions", () => {
  it("offers exactly what the API's capabilities allow", () => {
    expect(itemActions(item("DRAFT", { canEdit: true, canSubmit: true, canDelete: true }))).toEqual({
      edit: true,
      submit: true,
      resubmit: false,
      delete: true,
      review: false,
      archive: false,
      unarchive: false,
      feature: false,
      unfeature: false,
      comment: false,
    });
  });

  it("calls a submit after requested changes a resubmission", () => {
    expect(itemActions(item("CHANGES_REQUESTED", { canEdit: true, canSubmit: true })).resubmit).toBe(true);
    expect(itemActions(item("DRAFT", { canSubmit: true })).resubmit).toBe(false);
  });

  it("offers the review moves to the reviewer of a submitted item", () => {
    const actions = itemActions(item("SUBMITTED", { canReview: true, canDelete: true }));
    expect(actions.review).toBe(true);
    expect(actions.delete).toBe(true);
    expect(actions.submit).toBe(false);
  });

  it("turns the feature button the way the item stands", () => {
    expect(itemActions(item("APPROVED", { canFeature: true, canArchive: true })).feature).toBe(true);
    const featured = itemActions(item("APPROVED", { canFeature: true }, { featured: true }));
    expect(featured.feature).toBe(false);
    expect(featured.unfeature).toBe(true);
  });

  it("offers unarchive only when the API says so", () => {
    expect(itemActions(item("ARCHIVED", { canUnarchive: true })).unarchive).toBe(true);
    expect(itemActions(item("ARCHIVED", {})).unarchive).toBe(false);
  });

  it("offers nothing to a viewer without capabilities", () => {
    expect(hasAnyAction(item("APPROVED", {}))).toBe(false);
    expect(hasAnyAction(item("APPROVED", { canComment: true }))).toBe(true);
  });
});

describe("review notes", () => {
  it("asks for a note unless approving", () => {
    expect(noteRequired("APPROVE")).toBe(false);
    expect(noteRequired("REQUEST_CHANGES")).toBe(true);
    expect(noteRequired("REJECT")).toBe(true);
    expect(noteProblem("REJECT", "  ")).toBe("required");
    expect(noteProblem("APPROVE", "")).toBeNull();
    expect(noteProblem("REQUEST_CHANGES", "x".repeat(501))).toBe("tooLong");
    expect(noteProblem("REQUEST_CHANGES", "Add a title")).toBeNull();
  });
});

describe("buildReviewBody", () => {
  const it1 = { visibility: "CLASS" } as PortfolioItem;

  it("sends a narrower audience only when approving", () => {
    expect(buildReviewBody(it1, "APPROVE", "", "GUARDIANS")).toEqual({ decision: "APPROVE", note: undefined, visibility: "GUARDIANS" });
    expect(buildReviewBody(it1, "APPROVE", " Great ", "CLASS")).toEqual({ decision: "APPROVE", note: "Great", visibility: undefined });
    expect(buildReviewBody(it1, "REQUEST_CHANGES", " Add a title ", "GUARDIANS")).toEqual({
      decision: "REQUEST_CHANGES",
      note: "Add a title",
      visibility: undefined,
    });
  });
});
