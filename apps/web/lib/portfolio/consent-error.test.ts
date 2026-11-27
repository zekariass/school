import { describe, expect, it } from "vitest";
import type { ProblemDetail } from "@school/api-client";
import { ApiRequestError } from "@/lib/api-error";
import { missingStudentIds, parseGateError } from "./consent-error";

const problem = (extra: Record<string, unknown>) =>
  new ApiRequestError(422, { type: "about:blank", title: "x", status: 422, ...extra } as unknown as ProblemDetail);

describe("parseGateError (portfolio)", () => {
  it("reads the children of portfolio.media_consent_missing with the API's detail", () => {
    const error = problem({
      code: "portfolio.media_consent_missing",
      detail: "Two children have no media consent.",
      students: [{ studentId: "s1", displayName: "Abebe" }, { studentId: "s2" }],
    });
    const gate = parseGateError(error);
    expect(gate).toEqual({
      kind: "consent_missing",
      detail: "Two children have no media consent.",
      students: [{ studentId: "s1", displayName: "Abebe" }, { studentId: "s2" }],
    });
    expect(gate && missingStudentIds(gate)).toEqual(["s1", "s2"]);
  });

  it("keeps ids only when the API hid the names, and skips malformed entries", () => {
    const gate = parseGateError(
      problem({ code: "portfolio.media_consent_missing", students: [null, 5, { displayName: "No id" }, { studentId: "ok", displayName: "" }] }),
    );
    expect(gate).toMatchObject({ kind: "consent_missing", students: [{ studentId: "ok" }] });
    expect(gate && "displayName" in (gate as { students: object[] }).students[0]!).toBe(false);
  });

  it("reads the confirmation refusal", () => {
    const gate = parseGateError(problem({ code: "portfolio.media_confirmation_required", detail: "Confirm first." }));
    expect(gate).toEqual({ kind: "confirmation_required", detail: "Confirm first." });
    expect(gate && missingStudentIds(gate)).toEqual([]);
  });

  it("ignores the feed's codes, other problems and other errors", () => {
    expect(parseGateError(problem({ code: "feed.media_consent_missing" }))).toBeNull();
    expect(parseGateError(problem({ code: "portfolio.invalid_state" }))).toBeNull();
    expect(parseGateError(new ApiRequestError(500))).toBeNull();
    expect(parseGateError(new Error("x"))).toBeNull();
    expect(parseGateError(null)).toBeNull();
  });
});
