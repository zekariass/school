import { describe, expect, it } from "vitest";
import { acceptAttribute, maxMegabytes, policyFor, restrictPolicy, typeLabels, validateFile, type MediaPolicies, type PurposePolicy } from "./policies";

const MB = 1024 * 1024;

const avatar: PurposePolicy = {
  purpose: "AVATAR",
  contextTypes: ["USER"],
  maxBytesByMimeType: { "image/jpeg": 10 * MB, "image/png": 10 * MB, "image/webp": 10 * MB },
};

const mixed: PurposePolicy = {
  purpose: "BRANDING",
  contextTypes: ["SCHOOL"],
  maxBytesByMimeType: { "image/jpeg": 10 * MB, "application/pdf": 25 * MB },
};

describe("validateFile", () => {
  it("accepts an allowed type within the limit", () => {
    expect(validateFile({ type: "image/jpeg", size: 2 * MB }, avatar)).toBeNull();
    expect(validateFile({ type: "image/jpeg", size: 10 * MB }, avatar)).toBeNull();
  });

  it("rejects empty files", () => {
    expect(validateFile({ type: "image/jpeg", size: 0 }, avatar)).toEqual({ code: "empty" });
  });

  it("rejects types the purpose does not allow, naming the allowed ones", () => {
    expect(validateFile({ type: "application/pdf", size: 1000 }, avatar)).toEqual({
      code: "type",
      types: ["JPEG", "PNG", "WebP"],
    });
    expect(validateFile({ type: "", size: 1000 }, avatar)).toMatchObject({ code: "type" });
  });

  it("rejects files above the limit of their own type", () => {
    expect(validateFile({ type: "image/png", size: 10 * MB + 1 }, avatar)).toEqual({ code: "size", maxMb: 10 });
    expect(validateFile({ type: "application/pdf", size: 20 * MB }, mixed)).toBeNull();
    expect(validateFile({ type: "image/jpeg", size: 20 * MB }, mixed)).toEqual({ code: "size", maxMb: 10 });
  });

  it("ignores case and parameters of the MIME type, like the API", () => {
    expect(validateFile({ type: "Image/JPEG; charset=binary", size: 1000 }, avatar)).toBeNull();
  });
});

describe("policy helpers", () => {
  const policies: MediaPolicies = { purposes: [avatar, mixed] };

  it("finds a purpose's policy", () => {
    expect(policyFor(policies, "AVATAR")).toBe(avatar);
    expect(policyFor(policies, "POST")).toBeUndefined();
  });

  it("builds the accept attribute and the largest limit", () => {
    expect(acceptAttribute(avatar)).toBe("image/jpeg,image/png,image/webp");
    expect(maxMegabytes(mixed)).toBe(25);
  });

  it("labels MIME types for people", () => {
    expect(typeLabels(["image/jpeg", "video/mp4", "text/csv"])).toEqual(["JPEG", "MP4", "text/csv"]);
  });
});

describe("restrictPolicy", () => {
  it("keeps only the allowed types, with their limits", () => {
    const pdfOnly = restrictPolicy(mixed, ["application/pdf"]);
    expect(pdfOnly.maxBytesByMimeType).toEqual({ "application/pdf": 25 * MB });
    expect(acceptAttribute(pdfOnly)).toBe("application/pdf");
    expect(validateFile({ type: "image/jpeg", size: 1000 }, pdfOnly)).toMatchObject({ code: "type", types: ["PDF"] });
    expect(validateFile({ type: "application/pdf", size: 1000 }, pdfOnly)).toBeNull();
  });

  it("does not change the original policy", () => {
    restrictPolicy(mixed, ["image/jpeg"]);
    expect(Object.keys(mixed.maxBytesByMimeType)).toHaveLength(2);
  });
});
