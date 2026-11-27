import { checkSchedule, defaultScheduleLocal } from "./schedule";
import { mayRequireAcknowledgement, type AnnouncementType } from "./view";

/**
 * The composer's state and what it sends. Pure: the clock and the school's time zone are arguments. What the caller
 * may choose (audience kinds, roles, types, channels) comes from `GET .../announcements/capabilities` and only
 * decides which controls are offered: the API checks every request and has the final say. Who actually receives an
 * announcement is decided by the API's audience rule; nothing here resolves it.
 */

export const MAX_TITLE = 200;
export const MAX_BODY = 5000;
/** The API accepts at most this many chosen people in one announcement. */
export const MAX_PEOPLE = 200;

export type AudienceKind = "SCHOOL" | "CAMPUS" | "GRADE_LEVEL" | "CLASS" | "USER";
export type RecipientRole = "GUARDIAN" | "STUDENT" | "STAFF";
export type Timing = "now" | "schedule" | "draft";
export type Channel = "IN_APP" | "PUSH" | "EMAIL" | "SMS";

/** A person chosen for an announcement. The name is only for the chip; it is never sent. */
export interface ChosenPerson {
  id: string;
  name: string;
}

export interface TranslationDraft {
  title: string;
  body: string;
}

export interface ComposerState {
  type: AnnouncementType;
  title: string;
  body: string;
  language: string;
  translations: Record<string, TranslationDraft>;
  kind: AudienceKind;
  campusIds: string[];
  gradeLevelIds: string[];
  classIds: string[];
  people: ChosenPerson[];
  roles: RecipientRole[];
  /** A saved announcement whose audience is several kinds or per-row roles: shown, kept as it is, not editable here. */
  audienceLocked: boolean;
  requiresAcknowledgement: boolean;
  email: boolean;
  sms: boolean;
  /** Push is on unless the author switches it off; in-app always is. */
  push: boolean;
  timing: Timing;
  scheduledLocal: string;
}

/** The part of `AnnouncementCapabilities` the composer reads. */
export interface CapsLike {
  canCompose: boolean;
  schoolWide: boolean;
  urgentAllowed: boolean;
  studentsEnabled: boolean;
  emailAvailable: boolean;
  smsAvailable: boolean;
  smsTypes: readonly AnnouncementType[];
  classes: readonly { id: string; name: string }[];
  timeZone?: string;
  smsMaxPerAnnouncement?: number;
  smsMaxPerDay?: number;
}

// --- what the caller may choose ------------------------------------------------------------

/** School managers address everybody; others only their own classes and chosen people of those. */
export function allowedKinds(caps: Pick<CapsLike, "schoolWide" | "classes">): AudienceKind[] {
  if (caps.schoolWide) return ["SCHOOL", "CAMPUS", "GRADE_LEVEL", "CLASS", "USER"];
  return caps.classes.length > 0 ? ["CLASS", "USER"] : [];
}

export function allowedRoles(caps: Pick<CapsLike, "schoolWide" | "studentsEnabled">): RecipientRole[] {
  const roles: RecipientRole[] = ["GUARDIAN"];
  if (caps.studentsEnabled) roles.push("STUDENT");
  if (caps.schoolWide) roles.push("STAFF");
  return roles;
}

export function allowedTypes(caps: Pick<CapsLike, "urgentAllowed">): AnnouncementType[] {
  return caps.urgentAllowed ? ["NORMAL", "IMPORTANT", "URGENT", "SAFETY"] : ["NORMAL", "IMPORTANT"];
}

/** SMS is offered when the school allows it for this type of announcement. */
export function smsOfferedFor(caps: Pick<CapsLike, "smsAvailable" | "smsTypes">, type: AnnouncementType): boolean {
  return caps.smsAvailable && caps.smsTypes.includes(type);
}

export function defaultKind(caps: Pick<CapsLike, "schoolWide" | "classes">): AudienceKind {
  return allowedKinds(caps)[0] ?? "CLASS";
}

// --- state ---------------------------------------------------------------------------------

/** `defaultLanguage` is the school's country's default language (D42): a new announcement starts in it. */
export function initialComposer(caps: CapsLike, timeZone: string, now: Date, defaultLanguage: string): ComposerState {
  return {
    type: "NORMAL",
    title: "",
    body: "",
    language: defaultLanguage,
    translations: {},
    kind: defaultKind(caps),
    campusIds: [],
    gradeLevelIds: [],
    classIds: caps.schoolWide || caps.classes.length !== 1 ? [] : [caps.classes[0]!.id],
    people: [],
    roles: ["GUARDIAN"],
    audienceLocked: false,
    requiresAcknowledgement: false,
    email: false,
    sms: false,
    push: true,
    timing: "now",
    scheduledLocal: defaultScheduleLocal(timeZone, now),
  };
}

/** Brings the state in line with what the caller may choose (after loading, and whenever the type changes). */
export function normalizeForCaps(state: ComposerState, caps: CapsLike): ComposerState {
  const kinds = allowedKinds(caps);
  const roles = allowedRoles(caps);
  const type = allowedTypes(caps).includes(state.type) ? state.type : "NORMAL";
  const kept = state.roles.filter((r) => roles.includes(r));
  const translations = { ...state.translations };
  delete translations[state.language];
  return {
    ...state,
    type,
    kind: kinds.includes(state.kind) ? state.kind : defaultKind(caps),
    roles: kept.length > 0 ? kept : ["GUARDIAN"],
    translations,
    requiresAcknowledgement: state.requiresAcknowledgement && mayRequireAcknowledgement(type),
    email: state.email && caps.emailAvailable,
    sms: state.sms && smsOfferedFor(caps, type),
  };
}

export function withType(state: ComposerState, type: AnnouncementType, caps: CapsLike): ComposerState {
  return normalizeForCaps({ ...state, type }, caps);
}

export function withLanguage(state: ComposerState, language: string): ComposerState {
  const translations = { ...state.translations };
  delete translations[language];
  return { ...state, language, translations };
}

/**
 * The languages the author can write in: those of the school's country (`country.languageCodes`), and the
 * language an existing announcement already has even when the country no longer lists it.
 */
export function composerLanguages(countryLanguages: readonly string[], current: string): string[] {
  return countryLanguages.includes(current) ? [...countryLanguages] : [current, ...countryLanguages];
}

/** The other languages a translation can be added for. */
export function translationLanguages(
  state: Pick<ComposerState, "language" | "translations">,
  countryLanguages: readonly string[],
): string[] {
  return countryLanguages.filter((l) => l !== state.language && !(l in state.translations));
}

export function withKind(state: ComposerState, kind: AudienceKind): ComposerState {
  return { ...state, kind };
}

export function toggleId(ids: readonly string[], id: string): string[] {
  return ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id];
}

export function toggleRole(roles: readonly RecipientRole[], role: RecipientRole): RecipientRole[] {
  return roles.includes(role) ? roles.filter((r) => r !== role) : [...roles, role];
}

export function addPeople(people: readonly ChosenPerson[], added: readonly ChosenPerson[]): ChosenPerson[] {
  const seen = new Set(people.map((p) => p.id));
  const out = [...people];
  for (const p of added) {
    if (!seen.has(p.id) && out.length < MAX_PEOPLE) {
      seen.add(p.id);
      out.push(p);
    }
  }
  return out;
}

// --- validation ----------------------------------------------------------------------------

export type ComposerProblem =
  | "titleRequired"
  | "titleTooLong"
  | "bodyRequired"
  | "bodyTooLong"
  | "audienceEmpty"
  | "rolesEmpty"
  | "translationIncomplete"
  | "scheduleInvalid"
  | "schedulePast"
  | "scheduleTooFar";

/** The audience as the form holds it: how many targets are chosen. */
export function targetCount(state: ComposerState): number {
  switch (state.kind) {
    case "SCHOOL":
      return 1;
    case "CAMPUS":
      return state.campusIds.length;
    case "GRADE_LEVEL":
      return state.gradeLevelIds.length;
    case "CLASS":
      return state.classIds.length;
    case "USER":
      return state.people.length;
  }
}

/** What must be fixed before the announcement can be saved as a draft. */
export function draftProblems(state: ComposerState): ComposerProblem[] {
  const problems: ComposerProblem[] = [];
  const title = state.title.trim();
  const body = state.body.trim();
  if (!title) problems.push("titleRequired");
  else if (state.title.length > MAX_TITLE) problems.push("titleTooLong");
  if (!body) problems.push("bodyRequired");
  else if (state.body.length > MAX_BODY) problems.push("bodyTooLong");
  if (!state.audienceLocked) {
    if (targetCount(state) === 0) problems.push("audienceEmpty");
    else if (state.kind !== "USER" && state.roles.length === 0) problems.push("rolesEmpty");
  }
  for (const t of Object.values(state.translations)) {
    const ok = t.title.trim() && t.body.trim() && t.title.length <= MAX_TITLE && t.body.length <= MAX_BODY;
    if (!ok) {
      problems.push("translationIncomplete");
      break;
    }
  }
  return problems;
}

/** Everything `draftProblems` checks, plus the schedule when one is chosen. */
export function composerProblems(state: ComposerState, timeZone: string, now: Date): ComposerProblem[] {
  const problems = draftProblems(state);
  if (state.timing === "schedule") {
    const check = checkSchedule(state.scheduledLocal, timeZone, now);
    if (!check.ok) problems.push(check.reason === "invalid" ? "scheduleInvalid" : check.reason === "past" ? "schedulePast" : "scheduleTooFar");
  }
  return problems;
}

// --- payloads ------------------------------------------------------------------------------

export interface AudienceBody {
  type: AudienceKind;
  campusId?: string;
  gradeLevelId?: string;
  classId?: string;
  userIds?: string[];
  roles?: RecipientRole[];
}

/** One audience entry per chosen campus, grade or class; chosen people travel together in one entry. */
export function buildAudiences(state: ComposerState): AudienceBody[] {
  const roles = [...state.roles];
  switch (state.kind) {
    case "SCHOOL":
      return [{ type: "SCHOOL", roles }];
    case "CAMPUS":
      return state.campusIds.map((campusId) => ({ type: "CAMPUS", campusId, roles }));
    case "GRADE_LEVEL":
      return state.gradeLevelIds.map((gradeLevelId) => ({ type: "GRADE_LEVEL", gradeLevelId, roles }));
    case "CLASS":
      return state.classIds.map((classId) => ({ type: "CLASS", classId, roles }));
    case "USER":
      // The named people are reached for whatever they are in the school; roles do not narrow them.
      return [{ type: "USER", userIds: state.people.map((p) => p.id), roles: ["GUARDIAN"] }];
  }
}

export function buildChannels(state: Pick<ComposerState, "push" | "email" | "sms">): Channel[] {
  const channels: Channel[] = ["IN_APP"];
  if (state.push) channels.push("PUSH");
  if (state.email) channels.push("EMAIL");
  if (state.sms) channels.push("SMS");
  return channels;
}

/** Translations the author filled in (an empty draft of one is dropped, not sent). */
export function buildTranslations(state: Pick<ComposerState, "language" | "translations">): Record<string, TranslationDraft> {
  const out: Record<string, TranslationDraft> = {};
  for (const [language, t] of Object.entries(state.translations)) {
    if (language === state.language) continue;
    out[language] = { title: t.title.trim(), body: t.body.trim() };
  }
  return out;
}

/** The body of the create call. It is always a draft: publishing and scheduling are separate, confirmed calls. */
export function buildCreateBody(state: ComposerState) {
  return {
    title: state.title.trim(),
    body: state.body.trim(),
    bodyLanguageCode: state.language,
    translations: buildTranslations(state),
    type: state.type,
    requiresAcknowledgement: state.requiresAcknowledgement && mayRequireAcknowledgement(state.type),
    channels: buildChannels(state),
    audiences: buildAudiences(state),
    mode: "DRAFT" as const,
  };
}

/** The body of a draft's or scheduled announcement's edit: everything may change; a locked audience is left alone. */
export function buildDraftUpdateBody(state: ComposerState) {
  const { audiences, mode, ...rest } = buildCreateBody(state);
  void mode;
  return state.audienceLocked ? rest : { ...rest, audiences };
}

/**
 * The body of an edit after publishing: only the text and its language. `material` must be chosen (the API answers
 * 400 `announcement.material_required` otherwise): null means "not chosen yet" and gives no body.
 */
export function buildPublishedEditBody(state: ComposerState, material: boolean | null, changeNote?: string) {
  if (material === null) return null;
  const note = changeNote?.trim();
  return {
    title: state.title.trim(),
    body: state.body.trim(),
    bodyLanguageCode: state.language,
    translations: buildTranslations(state),
    material,
    ...(note ? { changeNote: note } : {}),
  };
}

/** The instant to schedule at, or null when the picked local time is not acceptable. */
export function scheduleInstant(state: ComposerState, timeZone: string, now: Date): string | null {
  const check = checkSchedule(state.scheduledLocal, timeZone, now);
  return check.ok ? check.instant : null;
}

/** A stable text of a body: the same body keeps the same `Idempotency-Key` across retries, a changed body gets a new one. */
export function submissionFingerprint(body: unknown): string {
  return JSON.stringify(body);
}

// --- from a saved announcement --------------------------------------------------------------

interface AudienceView {
  type: AudienceKind;
  campusId?: string | null;
  gradeLevelId?: string | null;
  classId?: string | null;
  userId?: string | null;
  roles: readonly RecipientRole[];
}

interface AnnouncementLike {
  type: AnnouncementType;
  title: string;
  body?: string | null;
  bodyLanguageCode?: string | null;
  translations?: Record<string, { title: string; body: string }> | null;
  audiences?: readonly AudienceView[] | null;
  requiresAcknowledgement: boolean;
  channels?: readonly Channel[] | null;
}

/**
 * The form state of a saved announcement. When its audience is not one kind with one set of roles (made elsewhere,
 * say) the form shows it and keeps it as it is instead of guessing.
 */
export function composerFromAnnouncement(
  a: AnnouncementLike,
  caps: CapsLike,
  timeZone: string,
  now: Date,
  defaultLanguage: string,
): ComposerState {
  const base = initialComposer(caps, timeZone, now, defaultLanguage);
  const audiences = a.audiences ?? [];
  const kinds = new Set(audiences.map((x) => x.type));
  const roleKey = (x: AudienceView) => [...x.roles].sort().join(",");
  const mixed = kinds.size > 1 || new Set(audiences.filter((x) => x.type !== "USER").map(roleKey)).size > 1;
  const first = audiences[0];
  const channels = a.channels ?? [];
  return {
    ...base,
    type: a.type,
    title: a.title,
    body: a.body ?? "",
    language: a.bodyLanguageCode ?? defaultLanguage,
    translations: Object.fromEntries(Object.entries(a.translations ?? {}).map(([k, v]) => [k, { title: v.title, body: v.body }])),
    kind: first?.type ?? base.kind,
    campusIds: audiences.flatMap((x) => (x.type === "CAMPUS" && x.campusId ? [x.campusId] : [])),
    gradeLevelIds: audiences.flatMap((x) => (x.type === "GRADE_LEVEL" && x.gradeLevelId ? [x.gradeLevelId] : [])),
    classIds: audiences.flatMap((x) => (x.type === "CLASS" && x.classId ? [x.classId] : [])),
    // Names of chosen people are not sent back; the form shows how many and lets the author start the list over.
    people: audiences.flatMap((x) => (x.type === "USER" && x.userId ? [{ id: x.userId, name: "" }] : [])),
    roles: first && first.type !== "USER" ? [...first.roles] : ["GUARDIAN"],
    audienceLocked: mixed,
    requiresAcknowledgement: a.requiresAcknowledgement,
    push: channels.length === 0 || channels.includes("PUSH"),
    email: channels.includes("EMAIL"),
    sms: channels.includes("SMS"),
    timing: "now",
  };
}

/** True when the title or the text differs from the saved announcement (a published one may only change these). */
export function textChanged(state: Pick<ComposerState, "title" | "body" | "language" | "translations">, saved: AnnouncementLike): boolean {
  if (state.title.trim() !== saved.title.trim()) return true;
  if (state.body.trim() !== (saved.body ?? "").trim()) return true;
  if (state.language !== (saved.bodyLanguageCode ?? state.language)) return true;
  return JSON.stringify(sortedTranslations(buildTranslations(state))) !== JSON.stringify(sortedTranslations(saved.translations ?? {}));
}

function sortedTranslations(t: Record<string, { title: string; body: string }>) {
  return Object.keys(t)
    .sort()
    .map((k) => [k, t[k]!.title.trim(), t[k]!.body.trim()]);
}

/** True when the draft holds anything worth keeping (to ask before leaving the page). */
export function hasContent(state: Pick<ComposerState, "title" | "body">): boolean {
  return state.title.trim().length > 0 || state.body.trim().length > 0;
}
