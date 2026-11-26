import type { FeedMediaItem, Post, PostCreate, PostUpdate, StoryCreate } from "./queries";
import { checkSchedule, defaultScheduleLocal } from "./schedule";

/** The limits the API enforces (the API still decides). */
export const MAX_BODY = 5000;
export const MAX_MEDIA = 10;
export const MAX_TAGS = 50;
export const MAX_CAPTION = 300;
export const MAX_STORY_CAPTION = 500;

export type PostType = NonNullable<PostCreate["postType"]>;
export type PublishTiming = "now" | "schedule" | "draft";

export interface ComposerPhoto {
  mediaId: string;
  /** Alt text and caption: one field, so every photo can be described. */
  caption: string;
}

/**
 * Everything the author has typed or chosen. It lives in memory while the composer is open and is never lost when
 * the API refuses the post (the consent gate creates nothing, so the same state is simply sent again).
 */
export interface ComposerState {
  body: string;
  photos: ComposerPhoto[];
  studentIds: string[];
  commentsEnabled: boolean;
  postType: PostType;
  timing: PublishTiming;
  /** `yyyy-MM-ddTHH:mm` on the school's wall clock. */
  scheduledLocal: string;
  /** "I confirm no student without media consent appears in these photos": only meaningful with photos and no tag. */
  confirmNoUnconsented: boolean;
}

export function initialComposer(defaults: { commentsEnabled: boolean; timeZone: string; now: Date }): ComposerState {
  return {
    body: "",
    photos: [],
    studentIds: [],
    commentsEnabled: defaults.commentsEnabled,
    postType: "POST",
    timing: "now",
    scheduledLocal: defaultScheduleLocal(defaults.timeZone, defaults.now),
    confirmNoUnconsented: false,
  };
}

/** An existing post as composer state (for editing). */
export function composerFromPost(post: Post): ComposerState {
  return {
    body: post.body ?? "",
    photos: post.media.map((m: FeedMediaItem) => ({ mediaId: m.mediaId, caption: m.caption ?? "" })),
    studentIds: post.tags.map((t) => t.studentId),
    commentsEnabled: post.commentsEnabled ?? true,
    postType: post.postType,
    timing: "now",
    scheduledLocal: "",
    confirmNoUnconsented: false,
  };
}

/** True when the photos have no tagged student: the author must then confirm that nobody without consent appears. */
export function needsConfirmation(state: Pick<ComposerState, "photos" | "studentIds">): boolean {
  return state.photos.length > 0 && state.studentIds.length === 0;
}

export type ComposerProblem =
  | "empty"
  | "tooLong"
  | "tooManyPhotos"
  | "tooManyTags"
  | "captionTooLong"
  | "confirmRequired"
  | "scheduleInvalid"
  | "schedulePast"
  | "scheduleTooFar";

/** What is wrong with the state before anything is sent (the API checks again). */
export function composerProblems(state: ComposerState, timeZone: string, now: Date): ComposerProblem[] {
  const problems: ComposerProblem[] = [];
  const text = state.body.trim();
  if (!text && state.photos.length === 0) problems.push("empty");
  if (text.length > MAX_BODY) problems.push("tooLong");
  if (state.photos.length > MAX_MEDIA) problems.push("tooManyPhotos");
  if (state.studentIds.length > MAX_TAGS) problems.push("tooManyTags");
  if (state.photos.some((p) => p.caption.length > MAX_CAPTION)) problems.push("captionTooLong");
  if (needsConfirmation(state) && !state.confirmNoUnconsented) problems.push("confirmRequired");
  if (state.timing === "schedule") {
    const check = checkSchedule(state.scheduledLocal, timeZone, now);
    if (!check.ok) problems.push(check.reason === "invalid" ? "scheduleInvalid" : check.reason === "past" ? "schedulePast" : "scheduleTooFar");
  }
  return problems;
}

/**
 * Problems that block "Save draft": an empty draft or too long text. The confirmation and the schedule are not
 * needed to keep a draft (the gate runs when it is published).
 */
export function draftProblems(state: ComposerState): ComposerProblem[] {
  return composerProblems({ ...state, timing: "draft", confirmNoUnconsented: true }, "UTC", new Date(0));
}

const trimmed = (value: string) => value.trim();

/** The send-confirmation flag: only when the photos are untagged and the author ticked the box. */
export function confirmationFlag(state: ComposerState): boolean | undefined {
  return needsConfirmation(state) && state.confirmNoUnconsented ? true : undefined;
}

function mediaRefs(state: ComposerState) {
  return state.photos.map((p) => ({ mediaId: p.mediaId, caption: trimmed(p.caption) || undefined }));
}

/** The request for a new post. Null when the schedule is not valid (the caller has shown the problem). */
export function buildCreateBody(state: ComposerState, timeZone: string, now: Date): PostCreate | null {
  const base: PostCreate = {
    body: trimmed(state.body) || undefined,
    postType: state.postType,
    media: mediaRefs(state),
    studentIds: state.studentIds,
    commentsEnabled: state.commentsEnabled,
    confirmNoUnconsentedStudents: confirmationFlag(state),
  };
  if (state.timing === "draft") return { ...base, mode: "DRAFT" };
  if (state.timing === "schedule") {
    const check = checkSchedule(state.scheduledLocal, timeZone, now);
    return check.ok ? { ...base, mode: "SCHEDULE", scheduledFor: check.instant } : null;
  }
  return { ...base, mode: "PUBLISH" };
}

/** The request that replaces the text, photos and tags of an existing post (an empty text clears it). */
export function buildUpdateBody(state: ComposerState): PostUpdate {
  return {
    body: trimmed(state.body),
    postType: state.postType,
    media: mediaRefs(state),
    studentIds: state.studentIds,
    commentsEnabled: state.commentsEnabled,
    confirmNoUnconsentedStudents: confirmationFlag(state),
  };
}

/** A contributor never gives the confirmation: it is the approver's. */
export function buildContributionBody(state: Pick<ComposerState, "body" | "photos" | "studentIds">) {
  return {
    body: trimmed(state.body) || undefined,
    media: state.photos.map((p) => ({ mediaId: p.mediaId, caption: trimmed(p.caption) || undefined })),
    studentIds: state.studentIds,
  };
}

/** A story: one photo and/or a caption (up to 500 characters). */
export function buildStoryBody(state: { caption: string; mediaId: string | null; studentIds: string[]; confirmNoUnconsented: boolean }): StoryCreate | null {
  const caption = trimmed(state.caption);
  if (!caption && !state.mediaId) return null;
  if (caption.length > MAX_STORY_CAPTION) return null;
  return {
    caption: caption || undefined,
    mediaId: state.mediaId ?? undefined,
    studentIds: state.studentIds,
    confirmNoUnconsentedStudents: state.mediaId && state.studentIds.length === 0 && state.confirmNoUnconsented ? true : undefined,
  };
}

/** The state without some students (the author untags the children whose consent is missing). */
export function withoutStudents(state: ComposerState, ids: readonly string[]): ComposerState {
  const drop = new Set(ids);
  const studentIds = state.studentIds.filter((id) => !drop.has(id));
  return { ...state, studentIds, confirmNoUnconsented: false };
}

/** Changing photos or tags invalidates a confirmation the author gave for the old set. */
export function withPhotos(state: ComposerState, photos: ComposerPhoto[]): ComposerState {
  return { ...state, photos, confirmNoUnconsented: false };
}

export function withStudents(state: ComposerState, studentIds: string[]): ComposerState {
  return { ...state, studentIds, confirmNoUnconsented: false };
}

/**
 * The key identifying one logical submission. The same body keeps its Idempotency-Key (a retry after a network
 * failure never posts twice); a changed body (the author untagged a child) gets a new one.
 */
export function submissionFingerprint(body: unknown): string {
  return JSON.stringify(body);
}
