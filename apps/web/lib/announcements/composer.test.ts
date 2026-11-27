import { describe, expect, it } from "vitest";
import {
  MAX_BODY,
  MAX_PEOPLE,
  MAX_TITLE,
  addPeople,
  allowedKinds,
  allowedRoles,
  allowedTypes,
  buildAudiences,
  buildChannels,
  buildCreateBody,
  buildDraftUpdateBody,
  buildPublishedEditBody,
  buildTranslations,
  composerFromAnnouncement,
  composerLanguages,
  composerProblems,
  draftProblems,
  hasContent,
  initialComposer,
  normalizeForCaps,
  scheduleInstant,
  smsOfferedFor,
  submissionFingerprint,
  textChanged,
  toggleId,
  toggleRole,
  translationLanguages,
  withLanguage,
  withType,
  type CapsLike,
  type ComposerState,
} from "./composer";

const ZONE = "Africa/Addis_Ababa";
const NOW = new Date("2026-11-26T10:00:00Z");
const A = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f01";
const B = "0198b3a2-7c1e-7b34-9f4d-2a6c8e1d5f02";

const manager: CapsLike = {
  canCompose: true,
  schoolWide: true,
  urgentAllowed: true,
  studentsEnabled: false,
  emailAvailable: true,
  smsAvailable: true,
  smsTypes: ["URGENT", "SAFETY"],
  classes: [],
};
const teacher: CapsLike = {
  canCompose: true,
  schoolWide: false,
  urgentAllowed: false,
  studentsEnabled: false,
  emailAvailable: false,
  smsAvailable: false,
  smsTypes: [],
  classes: [
    { id: A, name: "Grade 1A" },
    { id: B, name: "Grade 2B" },
  ],
};
const oneClassTeacher: CapsLike = { ...teacher, classes: [{ id: A, name: "Grade 1A" }] };
const nobody: CapsLike = { ...teacher, canCompose: false, classes: [] };

// Invented language codes: the composer takes the languages of the school's country from the API.
const LANGS = ["xa", "xb"] as const;
const blank = (caps: CapsLike) => initialComposer(caps, ZONE, NOW, LANGS[0]);
const filled = (caps: CapsLike, patch: Partial<ComposerState> = {}): ComposerState => ({ ...blank(caps), title: "Trip", body: "We go on Friday.", ...patch });

describe("what the caller may choose", () => {
  it("school managers address every kind, others only their classes and chosen people", () => {
    expect(allowedKinds(manager)).toEqual(["SCHOOL", "CAMPUS", "GRADE_LEVEL", "CLASS", "USER"]);
    expect(allowedKinds(teacher)).toEqual(["CLASS", "USER"]);
    expect(allowedKinds(nobody)).toEqual([]);
  });

  it("offers STUDENT only when the school allows it and STAFF only to managers", () => {
    expect(allowedRoles(manager)).toEqual(["GUARDIAN", "STAFF"]);
    expect(allowedRoles({ ...manager, studentsEnabled: true })).toEqual(["GUARDIAN", "STUDENT", "STAFF"]);
    expect(allowedRoles(teacher)).toEqual(["GUARDIAN"]);
    expect(allowedRoles({ ...teacher, studentsEnabled: true })).toEqual(["GUARDIAN", "STUDENT"]);
  });

  it("offers URGENT and SAFETY only with the urgent permission", () => {
    expect(allowedTypes(manager)).toEqual(["NORMAL", "IMPORTANT", "URGENT", "SAFETY"]);
    expect(allowedTypes(teacher)).toEqual(["NORMAL", "IMPORTANT"]);
  });

  it("offers SMS for the types the school lists, and only when it is on", () => {
    expect(smsOfferedFor(manager, "URGENT")).toBe(true);
    expect(smsOfferedFor(manager, "NORMAL")).toBe(false);
    expect(smsOfferedFor({ ...manager, smsAvailable: false }, "URGENT")).toBe(false);
  });
});

describe("initial state", () => {
  it("starts school-wide for managers and on the class for a teacher with one class", () => {
    expect(blank(manager)).toMatchObject({ kind: "SCHOOL", classIds: [], roles: ["GUARDIAN"], push: true, email: false, sms: false, type: "NORMAL", timing: "now" });
    expect(blank(oneClassTeacher)).toMatchObject({ kind: "CLASS", classIds: [A] });
    expect(blank(teacher)).toMatchObject({ kind: "CLASS", classIds: [] });
  });

  it("proposes tomorrow 08:00 on the school's clock for a schedule", () => {
    expect(blank(manager).scheduledLocal).toBe("2026-11-27T08:00");
  });
});

describe("normalizeForCaps", () => {
  it("drops a type, roles and channels the caller may not use", () => {
    const state = filled(teacher, { type: "URGENT", roles: ["GUARDIAN", "STAFF"], email: true, sms: true, requiresAcknowledgement: true, kind: "SCHOOL" });
    const next = normalizeForCaps(state, teacher);
    expect(next.type).toBe("NORMAL");
    expect(next.roles).toEqual(["GUARDIAN"]);
    expect(next.email).toBe(false);
    expect(next.sms).toBe(false);
    expect(next.requiresAcknowledgement).toBe(false);
    expect(next.kind).toBe("CLASS");
  });

  it("keeps what is allowed", () => {
    const state = filled(manager, { type: "URGENT", roles: ["GUARDIAN", "STAFF"], email: true, sms: true, requiresAcknowledgement: true });
    expect(normalizeForCaps(state, manager)).toMatchObject({ type: "URGENT", roles: ["GUARDIAN", "STAFF"], email: true, sms: true, requiresAcknowledgement: true });
  });

  it("never leaves the roles empty", () => {
    expect(normalizeForCaps(filled(teacher, { roles: ["STAFF"] }), teacher).roles).toEqual(["GUARDIAN"]);
  });

  it("switching to a type without SMS turns SMS off, and NORMAL turns the acknowledgement off", () => {
    const urgent = filled(manager, { type: "URGENT", sms: true, requiresAcknowledgement: true });
    const normal = withType(urgent, "NORMAL", manager);
    expect(normal.sms).toBe(false);
    expect(normal.requiresAcknowledgement).toBe(false);
    expect(withType(urgent, "SAFETY", manager)).toMatchObject({ sms: true, requiresAcknowledgement: true });
    expect(withType(urgent, "URGENT", teacher).type).toBe("NORMAL");
  });
});

describe("payloads", () => {
  it("builds one audience entry per chosen class", () => {
    const state = filled(teacher, { kind: "CLASS", classIds: [A, B], roles: ["GUARDIAN"] });
    expect(buildAudiences(state)).toEqual([
      { type: "CLASS", classId: A, roles: ["GUARDIAN"] },
      { type: "CLASS", classId: B, roles: ["GUARDIAN"] },
    ]);
  });

  it("builds school, campus and grade audiences with the chosen roles", () => {
    expect(buildAudiences(filled(manager, { kind: "SCHOOL", roles: ["GUARDIAN", "STAFF"] }))).toEqual([{ type: "SCHOOL", roles: ["GUARDIAN", "STAFF"] }]);
    expect(buildAudiences(filled(manager, { kind: "CAMPUS", campusIds: [A] }))).toEqual([{ type: "CAMPUS", campusId: A, roles: ["GUARDIAN"] }]);
    expect(buildAudiences(filled(manager, { kind: "GRADE_LEVEL", gradeLevelIds: [A, B] }))).toEqual([
      { type: "GRADE_LEVEL", gradeLevelId: A, roles: ["GUARDIAN"] },
      { type: "GRADE_LEVEL", gradeLevelId: B, roles: ["GUARDIAN"] },
    ]);
  });

  it("sends chosen people in one entry, without role claims that could be refused", () => {
    const state = filled(teacher, {
      kind: "USER",
      people: [
        { id: A, name: "Almaz" },
        { id: B, name: "Bekele" },
      ],
      roles: ["GUARDIAN", "STAFF"],
    });
    expect(buildAudiences(state)).toEqual([{ type: "USER", userIds: [A, B], roles: ["GUARDIAN"] }]);
  });

  it("never sends a name or a stale target of another kind", () => {
    const body = buildCreateBody(filled(manager, { kind: "SCHOOL", classIds: [A], people: [{ id: B, name: "Secret Name" }] }));
    expect(JSON.stringify(body)).not.toContain("Secret Name");
    expect(JSON.stringify(body)).not.toContain(A);
    expect(body.audiences).toEqual([{ type: "SCHOOL", roles: ["GUARDIAN"] }]);
  });

  it("the create call is always a draft; publishing and scheduling are their own calls", () => {
    expect(buildCreateBody(filled(manager, { timing: "now" })).mode).toBe("DRAFT");
    expect(buildCreateBody(filled(manager, { timing: "schedule" })).mode).toBe("DRAFT");
  });

  it("trims the text and carries type, language, acknowledgement and channels", () => {
    const body = buildCreateBody(filled(manager, { title: "  Road closed  ", body: " Take the south gate. ", type: "URGENT", language: "am", requiresAcknowledgement: true, sms: true, email: true, push: true }));
    expect(body).toMatchObject({
      title: "Road closed",
      body: "Take the south gate.",
      bodyLanguageCode: "am",
      type: "URGENT",
      requiresAcknowledgement: true,
      channels: ["IN_APP", "PUSH", "EMAIL", "SMS"],
    });
  });

  it("never asks for an acknowledgement on a NORMAL announcement", () => {
    expect(buildCreateBody(filled(manager, { type: "NORMAL", requiresAcknowledgement: true })).requiresAcknowledgement).toBe(false);
  });

  it("always includes the in-app channel, push only when kept on", () => {
    expect(buildChannels({ push: false, email: false, sms: false })).toEqual(["IN_APP"]);
    expect(buildChannels({ push: true, email: false, sms: true })).toEqual(["IN_APP", "PUSH", "SMS"]);
  });

  it("sends translations that differ from the language of the text, trimmed", () => {
    const state = filled(manager, { language: "en", translations: { am: { title: " ርዕስ ", body: " አካል " }, en: { title: "x", body: "y" } } });
    expect(buildTranslations(state)).toEqual({ am: { title: "ርዕስ", body: "አካል" } });
  });

  it("an edit of a draft keeps a locked audience as it is", () => {
    const locked = filled(manager, { audienceLocked: true });
    expect(buildDraftUpdateBody(locked)).not.toHaveProperty("audiences");
    expect(buildDraftUpdateBody(locked)).not.toHaveProperty("mode");
    expect(buildDraftUpdateBody(filled(manager))).toHaveProperty("audiences");
  });

  it("the same body keeps the same fingerprint, a changed body does not", () => {
    const a = buildCreateBody(filled(manager));
    expect(submissionFingerprint(a)).toBe(submissionFingerprint(buildCreateBody(filled(manager))));
    expect(submissionFingerprint(a)).not.toBe(submissionFingerprint(buildCreateBody(filled(manager, { title: "Other" }))));
  });
});

describe("edit after publishing (the material choice)", () => {
  it("gives no body until the author says whether people are told", () => {
    expect(buildPublishedEditBody(filled(manager), null)).toBeNull();
  });

  it("sends only the text, its language, the translations and the choice", () => {
    const state = filled(manager, { title: "New title", body: "New text", language: "en", translations: { am: { title: "t", body: "b" } }, type: "URGENT", kind: "CLASS", classIds: [A] });
    const body = buildPublishedEditBody(state, true, "  fixed the time ");
    expect(body).toEqual({ title: "New title", body: "New text", bodyLanguageCode: "en", translations: { am: { title: "t", body: "b" } }, material: true, changeNote: "fixed the time" });
    expect(body).not.toHaveProperty("audiences");
    expect(body).not.toHaveProperty("type");
    expect(body).not.toHaveProperty("channels");
    expect(body).not.toHaveProperty("requiresAcknowledgement");
  });

  it("a silent correction is material: false, still explicit", () => {
    expect(buildPublishedEditBody(filled(manager), false)).toMatchObject({ material: false });
    expect(buildPublishedEditBody(filled(manager), false)).not.toHaveProperty("changeNote");
  });

  it("notices whether the text changed", () => {
    const saved = { type: "NORMAL" as const, title: "Trip", body: "We go on Friday.", bodyLanguageCode: LANGS[0], translations: {}, requiresAcknowledgement: false };
    expect(textChanged(filled(manager), saved)).toBe(false);
    expect(textChanged(filled(manager, { body: "We go on Monday." }), saved)).toBe(true);
    expect(textChanged(filled(manager, { title: "  Trip  " }), saved)).toBe(false);
    expect(textChanged(filled(manager, { translations: { am: { title: "a", body: "b" } } }), saved)).toBe(true);
  });
});

describe("validation", () => {
  it("needs a title, a body and an audience", () => {
    expect(draftProblems(blank(manager))).toEqual(["titleRequired", "bodyRequired"]);
    expect(draftProblems(filled(manager))).toEqual([]);
    expect(draftProblems(filled(teacher))).toEqual(["audienceEmpty"]);
    expect(draftProblems(filled(manager, { title: "   ", body: "\n" }))).toEqual(["titleRequired", "bodyRequired"]);
  });

  it("limits the lengths", () => {
    expect(draftProblems(filled(manager, { title: "x".repeat(MAX_TITLE + 1) }))).toEqual(["titleTooLong"]);
    expect(draftProblems(filled(manager, { body: "x".repeat(MAX_BODY + 1) }))).toEqual(["bodyTooLong"]);
    expect(draftProblems(filled(manager, { title: "x".repeat(MAX_TITLE), body: "x".repeat(MAX_BODY) }))).toEqual([]);
  });

  it("needs a role for a group audience but not for chosen people", () => {
    expect(draftProblems(filled(manager, { roles: [] }))).toEqual(["rolesEmpty"]);
    expect(draftProblems(filled(manager, { kind: "USER", roles: [], people: [{ id: A, name: "A" }] }))).toEqual([]);
    expect(draftProblems(filled(manager, { kind: "USER", people: [] }))).toEqual(["audienceEmpty"]);
  });

  it("wants both parts of every translation", () => {
    expect(draftProblems(filled(manager, { translations: { am: { title: "ርዕስ", body: "" } } }))).toEqual(["translationIncomplete"]);
    expect(draftProblems(filled(manager, { translations: { am: { title: "ርዕስ", body: "አካል" } } }))).toEqual([]);
  });

  it("does not ask for an audience when it is kept as it is", () => {
    expect(draftProblems(filled(teacher, { audienceLocked: true }))).toEqual([]);
  });

  it("checks the schedule on the school's clock", () => {
    const base = filled(manager, { timing: "schedule" });
    expect(composerProblems({ ...base, scheduledLocal: "2026-11-27T08:00" }, ZONE, NOW)).toEqual([]);
    expect(composerProblems({ ...base, scheduledLocal: "2026-11-26T09:00" }, ZONE, NOW)).toEqual(["schedulePast"]);
    expect(composerProblems({ ...base, scheduledLocal: "2028-01-01T09:00" }, ZONE, NOW)).toEqual(["scheduleTooFar"]);
    expect(composerProblems({ ...base, scheduledLocal: "" }, ZONE, NOW)).toEqual(["scheduleInvalid"]);
    // the schedule only matters when it is chosen
    expect(composerProblems({ ...filled(manager, { timing: "now" }), scheduledLocal: "" }, ZONE, NOW)).toEqual([]);
  });

  it("converts the picked time to the instant to send", () => {
    expect(scheduleInstant({ ...filled(manager), scheduledLocal: "2026-11-27T08:00" }, ZONE, NOW)).toBe("2026-11-27T05:00:00.000Z");
    expect(scheduleInstant({ ...filled(manager), scheduledLocal: "2026-11-20T08:00" }, ZONE, NOW)).toBeNull();
  });
});

describe("helpers", () => {
  it("toggles ids and roles", () => {
    expect(toggleId([A], B)).toEqual([A, B]);
    expect(toggleId([A, B], A)).toEqual([B]);
    expect(toggleRole(["GUARDIAN"], "STAFF")).toEqual(["GUARDIAN", "STAFF"]);
    expect(toggleRole(["GUARDIAN", "STAFF"], "GUARDIAN")).toEqual(["STAFF"]);
  });

  it("adds people once and never beyond the API's limit", () => {
    expect(addPeople([{ id: A, name: "A" }], [{ id: A, name: "A again" }, { id: B, name: "B" }])).toEqual([
      { id: A, name: "A" },
      { id: B, name: "B" },
    ]);
    const many = Array.from({ length: MAX_PEOPLE }, (_, i) => ({ id: `p${i}`, name: "x" }));
    expect(addPeople(many, [{ id: "extra", name: "x" }])).toHaveLength(MAX_PEOPLE);
  });

  it("offers translations for the other languages only, and drops a translation that becomes the text's language", () => {
    expect(translationLanguages({ language: "xa", translations: {} }, LANGS)).toEqual(["xb"]);
    expect(translationLanguages({ language: "xa", translations: { xb: { title: "", body: "" } } }, LANGS)).toEqual([]);
    const state = filled(manager, { language: "xa", translations: { xb: { title: "a", body: "b" } } });
    expect(withLanguage(state, "xb").translations).toEqual({});
  });

  it("starts in the default language and lists the languages of the country, plus the one an old text already has", () => {
    expect(blank(manager).language).toBe("xa");
    expect(composerLanguages(LANGS, "xb")).toEqual(["xa", "xb"]);
    expect(composerLanguages(LANGS, "xz")).toEqual(["xz", "xa", "xb"]);
    expect(translationLanguages({ language: "xz", translations: {} }, LANGS)).toEqual(["xa", "xb"]);
  });

  it("knows when there is something to lose", () => {
    expect(hasContent(blank(manager))).toBe(false);
    expect(hasContent(filled(manager))).toBe(true);
  });
});

describe("composerFromAnnouncement", () => {
  const saved = {
    type: "IMPORTANT" as const,
    title: "Trip",
    body: "We go on Friday.",
    bodyLanguageCode: "am",
    translations: { en: { title: "Trip", body: "We go on Friday." } },
    requiresAcknowledgement: true,
    channels: ["IN_APP", "EMAIL"] as ("IN_APP" | "EMAIL")[],
    audiences: [{ type: "CLASS" as const, classId: A, roles: ["GUARDIAN" as const] }, { type: "CLASS" as const, classId: B, roles: ["GUARDIAN" as const] }],
  };

  it("restores the fields of a draft", () => {
    const state = composerFromAnnouncement(saved, manager, ZONE, NOW, LANGS[0]);
    expect(state).toMatchObject({ type: "IMPORTANT", title: "Trip", language: "am", kind: "CLASS", classIds: [A, B], roles: ["GUARDIAN"], requiresAcknowledgement: true, email: true, push: false, audienceLocked: false });
  });

  it("locks an audience made of several kinds or different roles instead of guessing", () => {
    const mixed = { ...saved, audiences: [{ type: "SCHOOL" as const, roles: ["GUARDIAN" as const] }, { type: "CLASS" as const, classId: A, roles: ["GUARDIAN" as const] }] };
    expect(composerFromAnnouncement(mixed, manager, ZONE, NOW, LANGS[0]).audienceLocked).toBe(true);
    const roles = { ...saved, audiences: [{ type: "CLASS" as const, classId: A, roles: ["GUARDIAN" as const] }, { type: "CLASS" as const, classId: B, roles: ["STAFF" as const] }] };
    expect(composerFromAnnouncement(roles, manager, ZONE, NOW, LANGS[0]).audienceLocked).toBe(true);
  });

  it("holds chosen people by id only", () => {
    const people = { ...saved, audiences: [{ type: "USER" as const, userId: A, roles: ["GUARDIAN" as const] }, { type: "USER" as const, userId: B, roles: ["GUARDIAN" as const] }] };
    const state = composerFromAnnouncement(people, manager, ZONE, NOW, LANGS[0]);
    expect(state.kind).toBe("USER");
    expect(state.people.map((p) => p.id)).toEqual([A, B]);
    expect(state.audienceLocked).toBe(false);
  });
});
