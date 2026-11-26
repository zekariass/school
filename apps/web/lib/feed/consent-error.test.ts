import { describe, expect, it } from "vitest";
import type { ProblemDetail } from "@school/api-client";
import { ApiRequestError } from "@/lib/api-error";
import { missingStudentIds, parseGateError } from "./consent-error";

const problem = (extra: Record<string, unknown>) =>
  new ApiRequestError(422, { type: "about:blank", title: "x", status: 422, ...extra } as unknown as ProblemDetail);

describe("parseGateError", () => {
  it("reads the students of feed.media_consent_missing with the API's detail", () => {
    const error = problem({
      code: "feed.media_consent_missing",
      detail: "Some children have no media consent.",
      students: [{ studentId: "s1", displayName: "Abebe" }, { studentId: "s2" }],
    });
    expect(parseGateError(error)).toEqual({
      kind: "consent_missing",
      detail: "Some children have no media consent.",
      students: [{ studentId: "s1", displayName: "Abebe" }, { studentId: "s2" }],
    });
  });

  it("keeps ids only when the API hid the names", () => {
    const gate = parseGateError(problem({ code: "feed.media_consent_missing", students: [{ studentId: "s9", displayName: "" }] }));
    expect(gate).toMatchObject({ kind: "consent_missing", students: [{ studentId: "s9" }] });
  });

  it("skips malformed student entries instead of failing", () => {
    const gate = parseGateError(problem({ code: "feed.media_consent_missing", students: [null, 5, { displayName: "No id" }, { studentId: "ok" }] }));
    expect(gate).toMatchObject({ students: [{ studentId: "ok" }] });
    expect(parseGateError(problem({ code: "feed.media_consent_missing" }))).toMatchObject({ students: [] });
  });

  it("recognizes the untagged-photo confirmation", () => {
    expect(parseGateError(problem({ code: "feed.media_confirmation_required", detail: "Confirm." }))).toEqual({
      kind: "confirmation_required",
      detail: "Confirm.",
    });
  });

  it("is null for every other error", () => {
    expect(parseGateError(problem({ code: "feed.post_empty" }))).toBeNull();
    expect(parseGateError(new ApiRequestError(500))).toBeNull();
    expect(parseGateError(new Error("boom"))).toBeNull();
    expect(parseGateError(undefined)).toBeNull();
  });
});

describe("missingStudentIds", () => {
  it("lists who to untag", () => {
    const gate = parseGateError(problem({ code: "feed.media_consent_missing", students: [{ studentId: "a" }, { studentId: "b" }] }))!;
    expect(missingStudentIds(gate)).toEqual(["a", "b"]);
    expect(missingStudentIds({ kind: "confirmation_required" })).toEqual([]);
  });
});
