import { describe, expect, it } from "vitest";
import {
  MAX_FILES,
  MAX_TITLE,
  allowedMimeTypes,
  buildCreateBody,
  buildQuickCaptureBody,
  buildUpdateBody,
  confirmationFlag,
  formFromItem,
  formProblems,
  initialForm,
  needsConfirmation,
  offeredTypes,
  submissionFingerprint,
  withFiles,
  withStudents,
  withType,
  withoutStudents,
  type ItemFormState,
} from "./composer";
import type { PortfolioItem } from "./queries";

const photo = (id: string) => ({ mediaId: id, kind: "IMAGE" as const });

const filled = (patch: Partial<ItemFormState> = {}): ItemFormState => ({
  ...initialForm(),
  itemType: "PHOTO",
  title: "  My drawing of a tree  ",
  files: [photo("m1")],
  ...patch,
});

describe("offeredTypes", () => {
  it("offers the types the web builds, as far as the API lists them", () => {
    expect(offeredTypes(["TEXT", "PHOTO", "DRAWING", "SCAN", "DOCUMENT"])).toEqual(["TEXT", "PHOTO", "SCAN", "DOCUMENT"]);
    expect(offeredTypes(["TEXT", "DOCUMENT"])).toEqual(["TEXT", "DOCUMENT"]);
    expect(offeredTypes(["AUDIO", "VIDEO"])).toEqual([]);
  });
  it("offers them all before the capabilities are known", () => {
    expect(offeredTypes(undefined)).toEqual(["TEXT", "PHOTO", "SCAN", "DOCUMENT"]);
  });
});

describe("allowedMimeTypes", () => {
  it("fits the file types to the item type", () => {
    expect(allowedMimeTypes("PHOTO")).not.toContain("application/pdf");
    expect(allowedMimeTypes("DOCUMENT")).toEqual(["application/pdf"]);
    expect(allowedMimeTypes("SCAN")).toContain("application/pdf");
    expect(allowedMimeTypes("SCAN")).toContain("image/jpeg");
    expect(allowedMimeTypes("TEXT")).toEqual([]);
  });
});

describe("formProblems", () => {
  it("needs a title, even for a draft", () => {
    expect(formProblems(initialForm(), false)).toEqual(["titleRequired"]);
    expect(formProblems(filled({ title: "   " }), false)).toEqual(["titleRequired"]);
    expect(formProblems(filled({ title: "x".repeat(MAX_TITLE + 1) }), false)).toEqual(["titleTooLong"]);
  });

  it("a submission needs the text of a text item or the files of the others", () => {
    expect(formProblems({ ...initialForm(), title: "T" }, false)).toEqual([]);
    expect(formProblems({ ...initialForm(), title: "T" }, true)).toEqual(["textRequired"]);
    expect(formProblems(filled({ files: [] }), false)).toEqual([]);
    expect(formProblems(filled({ files: [] }), true)).toEqual(["filesRequired"]);
    expect(formProblems(filled(), true)).toEqual([]);
  });

  it("bounds the text fields, the files and the tags", () => {
    const many = Array.from({ length: MAX_FILES + 1 }, (_, i) => photo(`m${i}`));
    expect(formProblems(filled({ files: many }), false)).toEqual(["tooManyFiles"]);
    expect(formProblems(filled({ description: "d".repeat(1001), reflection: "r".repeat(1001) }), false)).toEqual([
      "descriptionTooLong",
      "reflectionTooLong",
    ]);
    expect(formProblems({ ...initialForm(), title: "T", textContent: "t".repeat(5001) }, false)).toEqual(["textTooLong"]);
    expect(formProblems(filled({ taggedStudentIds: Array.from({ length: 21 }, (_, i) => `s${i}`) }), false)).toEqual(["tooManyTags"]);
  });
});

describe("confirmation", () => {
  it("is asked for only with files and no tag", () => {
    expect(needsConfirmation(filled())).toBe(true);
    expect(needsConfirmation(filled({ taggedStudentIds: ["s1"] }))).toBe(false);
    expect(needsConfirmation(filled({ files: [] }))).toBe(false);
  });

  it("is sent only when ticked and still needed", () => {
    expect(confirmationFlag(filled({ confirmNoUnconsented: true }))).toBe(true);
    expect(confirmationFlag(filled())).toBeUndefined();
    expect(confirmationFlag(filled({ confirmNoUnconsented: true, taggedStudentIds: ["s1"] }))).toBeUndefined();
  });

  it("is dropped when the files, the tags or the type change", () => {
    const ticked = filled({ confirmNoUnconsented: true });
    expect(withFiles(ticked, [photo("m1"), photo("m2")]).confirmNoUnconsented).toBe(false);
    expect(withStudents(ticked, ["s1"]).confirmNoUnconsented).toBe(false);
    expect(withoutStudents(filled({ taggedStudentIds: ["s1", "s2"], confirmNoUnconsented: true }), ["s1"])).toMatchObject({
      taggedStudentIds: ["s2"],
      confirmNoUnconsented: false,
    });
    expect(withType(ticked, "DOCUMENT")).toMatchObject({ itemType: "DOCUMENT", files: [], confirmNoUnconsented: false });
    expect(withType(ticked, "PHOTO")).toBe(ticked);
  });
});

describe("buildCreateBody", () => {
  it("trims the text and leaves out what is empty", () => {
    expect(buildCreateBody(filled(), "DRAFT")).toEqual({
      itemType: "PHOTO",
      title: "My drawing of a tree",
      mode: "DRAFT",
      visibility: "GUARDIANS",
      mediaIds: ["m1"],
      taggedStudentIds: [],
      description: undefined,
      reflection: undefined,
      textContent: undefined,
      workDate: undefined,
      subjectId: undefined,
      classId: undefined,
      confirmNoUnconsentedStudents: undefined,
    });
  });

  it("sends the class, subject, date, reflection and the confirmation when chosen", () => {
    const body = buildCreateBody(
      filled({ classId: "c1", subjectId: "su1", workDate: "2026-11-20", reflection: " I learned to mix colours ", confirmNoUnconsented: true, visibility: "CLASS" }),
      "SUBMIT",
    );
    expect(body).toMatchObject({
      mode: "SUBMIT",
      classId: "c1",
      subjectId: "su1",
      workDate: "2026-11-20",
      reflection: "I learned to mix colours",
      confirmNoUnconsentedStudents: true,
      visibility: "CLASS",
    });
  });

  it("a text item carries its text and no files or tags", () => {
    const body = buildCreateBody({ ...initialForm(), title: "Poem", textContent: " Roses are red ", files: [photo("old")], taggedStudentIds: ["s1"] }, "SUBMIT");
    expect(body.textContent).toBe("Roses are red");
    expect(body.mediaIds).toBeUndefined();
    expect(body.taggedStudentIds).toBeUndefined();
    expect(body.confirmNoUnconsentedStudents).toBeUndefined();
  });
});

describe("buildUpdateBody", () => {
  it("replaces what the form shows and lets an emptied description clear it", () => {
    const body = buildUpdateBody(filled({ description: "  ", reflection: "New" }));
    expect(body).toMatchObject({ title: "My drawing of a tree", description: "", reflection: "New", mediaIds: ["m1"], showsOtherChildren: false });
  });

  it("follows the tags for 'other children appear'", () => {
    expect(buildUpdateBody(filled({ taggedStudentIds: ["s1"] })).showsOtherChildren).toBe(true);
    expect(buildUpdateBody({ ...initialForm(), title: "T", textContent: "Hi" }).showsOtherChildren).toBeUndefined();
  });
});

describe("buildQuickCaptureBody", () => {
  it("names the student and always states whether other children appear", () => {
    const body = buildQuickCaptureBody(filled({ showsOtherChildren: false }), "stu1");
    expect(body).toMatchObject({ studentId: "stu1", itemType: "PHOTO", showsOtherChildren: false, mediaIds: ["m1"] });
    expect(buildQuickCaptureBody(filled({ showsOtherChildren: true }), "stu1").showsOtherChildren).toBe(true);
    expect(buildQuickCaptureBody(filled({ showsOtherChildren: false, taggedStudentIds: ["s2"] }), "stu1").showsOtherChildren).toBe(true);
  });

  it("a text capture has no files and says nothing about other children", () => {
    const body = buildQuickCaptureBody({ ...initialForm(), title: "Spoke well", textContent: "In class" }, "stu1");
    expect(body.mediaIds).toBeUndefined();
    expect(body.showsOtherChildren).toBeUndefined();
  });
});

describe("formFromItem", () => {
  it("fills the form from an item to edit", () => {
    const item = {
      itemType: "SCAN",
      title: "Maths",
      description: "d",
      reflection: undefined,
      workDate: "2026-11-01",
      classId: "c1",
      visibility: "CLASS",
      media: [{ mediaId: "m9", kind: "DOCUMENT" }],
      taggedStudents: [{ studentId: "s3" }],
      mediaConfirmed: false,
      showsOtherChildren: true,
    } as unknown as PortfolioItem;
    expect(formFromItem(item)).toMatchObject({
      itemType: "SCAN",
      title: "Maths",
      reflection: "",
      workDate: "2026-11-01",
      classId: "c1",
      visibility: "CLASS",
      files: [{ mediaId: "m9", kind: "DOCUMENT" }],
      taggedStudentIds: ["s3"],
      confirmNoUnconsented: false,
    });
  });
});

describe("submissionFingerprint", () => {
  it("is the same for the same body and different for a changed one", () => {
    const a = buildCreateBody(filled(), "SUBMIT");
    expect(submissionFingerprint(a)).toBe(submissionFingerprint(buildCreateBody(filled(), "SUBMIT")));
    expect(submissionFingerprint(a)).not.toBe(submissionFingerprint(buildCreateBody(filled({ title: "Other" }), "SUBMIT")));
  });
});
