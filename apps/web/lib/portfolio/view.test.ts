import { describe, expect, it } from "vitest";
import { cleanComment, commentProblem, MAX_COMMENT } from "./comments";
import {
  coverFile,
  documentFiles,
  imageFiles,
  isCapped,
  isHistoryAction,
  mayReadHistory,
  reviewVisibilityChoices,
  statusTone,
  visibilityChoices,
  visibilityRank,
} from "./view";

describe("visibility", () => {
  it("orders the audiences from narrowest to widest", () => {
    expect(visibilityRank("STUDENT_TEACHER")).toBeLessThan(visibilityRank("GUARDIANS"));
    expect(visibilityRank("CLASS")).toBeLessThan(visibilityRank("SCHOOL"));
  });

  it("offers the audiences up to the school's cap", () => {
    expect(visibilityChoices("GUARDIANS")).toEqual(["STUDENT_TEACHER", "GUARDIANS"]);
    expect(visibilityChoices("SCHOOL")).toEqual(["STUDENT_TEACHER", "GUARDIANS", "CLASS", "SCHOOL"]);
    expect(visibilityChoices("STUDENT_TEACHER")).toEqual(["STUDENT_TEACHER"]);
    expect(visibilityChoices(undefined)).toEqual(["STUDENT_TEACHER", "GUARDIANS"]);
  });

  it("lets a reviewer narrow the audience, never widen it", () => {
    expect(reviewVisibilityChoices("CLASS")).toEqual(["STUDENT_TEACHER", "GUARDIANS", "CLASS"]);
    expect(reviewVisibilityChoices("STUDENT_TEACHER")).toEqual(["STUDENT_TEACHER"]);
  });

  it("notices when the school's cap lowered the chosen audience", () => {
    expect(isCapped({ visibility: "CLASS", effectiveVisibility: "GUARDIANS" })).toBe(true);
    expect(isCapped({ visibility: "GUARDIANS", effectiveVisibility: "GUARDIANS" })).toBe(false);
  });
});

describe("statusTone", () => {
  it("gives each status a tone", () => {
    expect(statusTone("DRAFT")).toBe("neutral");
    expect(statusTone("SUBMITTED")).toBe("pending");
    expect(statusTone("CHANGES_REQUESTED")).toBe("attention");
    expect(statusTone("APPROVED")).toBe("good");
    expect(statusTone("REJECTED")).toBe("bad");
    expect(statusTone("ARCHIVED")).toBe("neutral");
  });
});

describe("mayReadHistory", () => {
  it("follows what the API sends only to the student and the reviewers", () => {
    expect(mayReadHistory({ taggedStudents: [] })).toBe(true);
    expect(mayReadHistory({ taggedStudents: [{ studentId: "s1" }] })).toBe(true);
    expect(mayReadHistory({ taggedStudents: undefined })).toBe(false);
    expect(mayReadHistory({ taggedStudents: null as unknown as undefined })).toBe(false);
  });
});

describe("files", () => {
  const media = [
    { mediaId: "a", kind: "DOCUMENT" },
    { mediaId: "b", kind: "IMAGE" },
    { mediaId: "c", kind: "IMAGE" },
  ];
  it("splits images from documents and picks the cover", () => {
    expect(imageFiles(media).map((m) => m.mediaId)).toEqual(["b", "c"]);
    expect(documentFiles(media).map((m) => m.mediaId)).toEqual(["a"]);
    expect(coverFile(media)?.mediaId).toBe("b");
    expect(coverFile([])).toBeUndefined();
  });
});

describe("commentProblem", () => {
  it("needs some text, at most 500 characters", () => {
    expect(commentProblem("   ")).toBe("empty");
    expect(commentProblem("Well done!")).toBeNull();
    expect(commentProblem("x".repeat(MAX_COMMENT))).toBeNull();
    expect(commentProblem("x".repeat(MAX_COMMENT + 1))).toBe("tooLong");
    expect(cleanComment("  hi ")).toBe("hi");
  });
});

describe("isHistoryAction", () => {
  it("knows the actions the API records", () => {
    expect(isHistoryAction("CAPTURED")).toBe(true);
    expect(isHistoryAction("UNFEATURED")).toBe(true);
    expect(isHistoryAction("SOMETHING_NEW")).toBe(false);
  });
});
