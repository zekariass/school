import { describe, expect, it } from "vitest";
import {
  buildContributionBody,
  buildCreateBody,
  buildStoryBody,
  buildUpdateBody,
  composerProblems,
  confirmationFlag,
  draftProblems,
  initialComposer,
  needsConfirmation,
  submissionFingerprint,
  withPhotos,
  withStudents,
  withoutStudents,
  type ComposerState,
} from "./composer";

const ZONE = "Africa/Addis_Ababa";
const NOW = new Date("2026-11-26T10:00:00Z");
const blank = initialComposer({ commentsEnabled: true, timeZone: ZONE, now: NOW });
const photo = (id: string, caption = "") => ({ mediaId: id, caption });
const state = (patch: Partial<ComposerState>): ComposerState => ({ ...blank, ...patch });

describe("initialComposer", () => {
  it("starts empty, publishing now, with the school's default for comments", () => {
    expect(blank).toMatchObject({ body: "", photos: [], studentIds: [], timing: "now", postType: "POST", commentsEnabled: true, confirmNoUnconsented: false });
    expect(initialComposer({ commentsEnabled: false, timeZone: ZONE, now: NOW }).commentsEnabled).toBe(false);
    // The suggested time is on the school's clock: tomorrow 08:00 Addis.
    expect(blank.scheduledLocal).toBe("2026-11-27T08:00");
  });
});

describe("the confirmation for untagged photos", () => {
  it("is needed only with photos and no tagged child", () => {
    expect(needsConfirmation(state({ photos: [photo("m1")] }))).toBe(true);
    expect(needsConfirmation(state({ photos: [photo("m1")], studentIds: ["s1"] }))).toBe(false);
    expect(needsConfirmation(state({ body: "text only" }))).toBe(false);
  });

  it("is sent only when ticked and still needed", () => {
    expect(confirmationFlag(state({ photos: [photo("m1")], confirmNoUnconsented: true }))).toBe(true);
    expect(confirmationFlag(state({ photos: [photo("m1")] }))).toBeUndefined();
    expect(confirmationFlag(state({ photos: [photo("m1")], studentIds: ["s1"], confirmNoUnconsented: true }))).toBeUndefined();
  });

  it("is dropped when the photos or the tags change: it was given for something else", () => {
    const confirmed = state({ photos: [photo("m1")], confirmNoUnconsented: true });
    expect(withPhotos(confirmed, [photo("m1"), photo("m2")]).confirmNoUnconsented).toBe(false);
    expect(withStudents(confirmed, ["s1"]).confirmNoUnconsented).toBe(false);
  });
});

describe("composerProblems", () => {
  it("needs some text or a photo", () => {
    expect(composerProblems(blank, ZONE, NOW)).toContain("empty");
    expect(composerProblems(state({ body: "   " }), ZONE, NOW)).toContain("empty");
    expect(composerProblems(state({ body: "Hello" }), ZONE, NOW)).toEqual([]);
  });

  it("limits text, photos, tags and photo descriptions", () => {
    expect(composerProblems(state({ body: "x".repeat(5001) }), ZONE, NOW)).toContain("tooLong");
    expect(composerProblems(state({ photos: Array.from({ length: 11 }, (_, i) => photo(`m${i}`)), confirmNoUnconsented: true }), ZONE, NOW)).toContain("tooManyPhotos");
    expect(composerProblems(state({ body: "a", studentIds: Array.from({ length: 51 }, (_, i) => `s${i}`) }), ZONE, NOW)).toContain("tooManyTags");
    expect(composerProblems(state({ photos: [photo("m1", "c".repeat(301))], studentIds: ["s1"] }), ZONE, NOW)).toContain("captionTooLong");
  });

  it("asks for the confirmation, but not for a draft", () => {
    const untagged = state({ photos: [photo("m1")] });
    expect(composerProblems(untagged, ZONE, NOW)).toContain("confirmRequired");
    expect(draftProblems(untagged)).toEqual([]);
    expect(draftProblems(blank)).toEqual(["empty"]);
  });

  it("checks the schedule in the school's zone", () => {
    const scheduled = (local: string) => state({ body: "Hi", timing: "schedule", scheduledLocal: local });
    expect(composerProblems(scheduled("2026-11-27T08:00"), ZONE, NOW)).toEqual([]);
    expect(composerProblems(scheduled("2026-11-26T09:00"), ZONE, NOW)).toEqual(["schedulePast"]);
    expect(composerProblems(scheduled("2027-06-01T09:00"), ZONE, NOW)).toEqual(["scheduleTooFar"]);
    expect(composerProblems(scheduled(""), ZONE, NOW)).toEqual(["scheduleInvalid"]);
  });
});

describe("buildCreateBody", () => {
  const tagged = state({ body: "  Garden day  ", photos: [photo("m1", " Seeds "), photo("m2")], studentIds: ["s1", "s2"], commentsEnabled: false, postType: "ACTIVITY" });

  it("publishes now", () => {
    expect(buildCreateBody(tagged, ZONE, NOW)).toEqual({
      body: "Garden day",
      postType: "ACTIVITY",
      media: [{ mediaId: "m1", caption: "Seeds" }, { mediaId: "m2", caption: undefined }],
      studentIds: ["s1", "s2"],
      commentsEnabled: false,
      confirmNoUnconsentedStudents: undefined,
      mode: "PUBLISH",
    });
  });

  it("saves a draft", () => {
    expect(buildCreateBody({ ...tagged, timing: "draft" }, ZONE, NOW)?.mode).toBe("DRAFT");
  });

  it("schedules with the instant of the school's wall clock", () => {
    const body = buildCreateBody({ ...tagged, timing: "schedule", scheduledLocal: "2026-12-01T09:30" }, ZONE, NOW);
    expect(body).toMatchObject({ mode: "SCHEDULE", scheduledFor: "2026-12-01T06:30:00.000Z" });
  });

  it("gives up on an invalid schedule instead of sending a wrong time", () => {
    expect(buildCreateBody({ ...tagged, timing: "schedule", scheduledLocal: "nonsense" }, ZONE, NOW)).toBeNull();
    expect(buildCreateBody({ ...tagged, timing: "schedule", scheduledLocal: "2026-11-26T00:00" }, ZONE, NOW)).toBeNull();
  });

  it("carries the confirmation only for untagged photos", () => {
    const untagged = state({ photos: [photo("m1")], confirmNoUnconsented: true });
    expect(buildCreateBody(untagged, ZONE, NOW)?.confirmNoUnconsentedStudents).toBe(true);
    expect(buildCreateBody({ ...untagged, studentIds: ["s1"] }, ZONE, NOW)?.confirmNoUnconsentedStudents).toBeUndefined();
  });

  it("never sends a text that is only spaces", () => {
    expect(buildCreateBody(state({ body: "   ", photos: [photo("m1")], studentIds: ["s1"] }), ZONE, NOW)?.body).toBeUndefined();
  });
});

describe("buildUpdateBody", () => {
  it("replaces text, photos and tags; an emptied text clears it", () => {
    const body = buildUpdateBody(state({ body: "  ", photos: [photo("m1")], studentIds: ["s1"] }));
    expect(body).toMatchObject({ body: "", media: [{ mediaId: "m1" }], studentIds: ["s1"] });
  });
});

describe("recovering from the consent gate", () => {
  it("removes the children without consent and keeps everything else", () => {
    const before = state({ body: "Hello", photos: [photo("m1", "Cap")], studentIds: ["s1", "s2", "s3"], confirmNoUnconsented: true });
    const after = withoutStudents(before, ["s2", "s9"]);
    expect(after.studentIds).toEqual(["s1", "s3"]);
    expect(after.body).toBe("Hello");
    expect(after.photos).toEqual(before.photos);
    expect(after.confirmNoUnconsented).toBe(false);
  });

  it("falls back to the confirmation when every tag is removed", () => {
    const after = withoutStudents(state({ photos: [photo("m1")], studentIds: ["s1"] }), ["s1"]);
    expect(needsConfirmation(after)).toBe(true);
  });
});

describe("contributions and stories", () => {
  it("a contribution never carries the confirmation: it is the approver's", () => {
    const body = buildContributionBody({ body: " Hi ", photos: [photo("m1")], studentIds: ["s1"] });
    expect(body).toEqual({ body: "Hi", media: [{ mediaId: "m1", caption: undefined }], studentIds: ["s1"] });
    expect("confirmNoUnconsentedStudents" in body).toBe(false);
  });

  it("a story needs a photo or words, within 500 characters", () => {
    expect(buildStoryBody({ caption: "", mediaId: null, studentIds: [], confirmNoUnconsented: false })).toBeNull();
    expect(buildStoryBody({ caption: "x".repeat(501), mediaId: null, studentIds: [], confirmNoUnconsented: false })).toBeNull();
    expect(buildStoryBody({ caption: " Hi ", mediaId: null, studentIds: [], confirmNoUnconsented: true })).toEqual({
      caption: "Hi",
      mediaId: undefined,
      studentIds: [],
      confirmNoUnconsentedStudents: undefined,
    });
    expect(buildStoryBody({ caption: "", mediaId: "m1", studentIds: [], confirmNoUnconsented: true })?.confirmNoUnconsentedStudents).toBe(true);
    expect(buildStoryBody({ caption: "", mediaId: "m1", studentIds: ["s1"], confirmNoUnconsented: true })?.confirmNoUnconsentedStudents).toBeUndefined();
  });
});

describe("submissionFingerprint", () => {
  it("is the same for the same request and different when a child is untagged", () => {
    const a = buildCreateBody(state({ body: "Hi", studentIds: ["s1", "s2"] }), ZONE, NOW);
    const b = buildCreateBody(state({ body: "Hi", studentIds: ["s1", "s2"] }), ZONE, NOW);
    const c = buildCreateBody(state({ body: "Hi", studentIds: ["s1"] }), ZONE, NOW);
    expect(submissionFingerprint(a)).toBe(submissionFingerprint(b));
    expect(submissionFingerprint(a)).not.toBe(submissionFingerprint(c));
  });
});
