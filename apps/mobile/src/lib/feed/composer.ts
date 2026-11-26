import type { components } from '@school/api-client';

import type { SchedulePick } from './schedule';
import type { PostType, PostView, PublishMode } from './types';

type S = components['schemas'];

export const MAX_POST_CHARS = 5000;
export const MAX_STORY_CHARS = 500;
export const MAX_PHOTOS = 10;
export const MAX_CAPTION_CHARS = 300;
export const MAX_TAGS = 50;

export type ComposerKind = 'post' | 'contribution' | 'story';
export type AttachmentStatus = 'preparing' | 'uploading' | 'ready' | 'failed';

/** A photo on its way: kept in memory while the screen is open, never written to storage by the app. */
export interface Attachment {
  /** Local key (the list key and the handle of its upload). */
  key: string;
  /** The converted JPEG on the device, for the preview. */
  uri: string;
  caption: string;
  status: AttachmentStatus;
  percent: number;
  /** Set when `status` is `ready`. */
  mediaId?: string;
  /** The already-localized reason of a failed upload. */
  failure?: string;
}

export interface ComposerState {
  kind: ComposerKind;
  body: string;
  postType: PostType;
  commentsEnabled: boolean;
  studentIds: string[];
  attachments: Attachment[];
  mode: PublishMode;
  schedule: SchedulePick | null;
  /** "I confirm no student without media consent appears in these photos": only for photos with nobody tagged. */
  confirmNoUnconsented: boolean;
}

export function initialComposer(kind: ComposerKind, commentsEnabled: boolean): ComposerState {
  return {
    kind,
    body: '',
    postType: 'POST',
    commentsEnabled,
    studentIds: [],
    attachments: [],
    mode: 'PUBLISH',
    schedule: null,
    confirmNoUnconsented: false,
  };
}

/** The composer filled from an existing post (edit). Photos come back as ready attachments (no preview: the signed link is fetched). */
export function composerFromPost(post: PostView): ComposerState {
  return {
    kind: post.contribution ? 'contribution' : 'post',
    body: post.body ?? '',
    postType: post.postType,
    commentsEnabled: post.commentsEnabled ?? true,
    studentIds: post.tags.map((tag) => tag.studentId),
    attachments: post.media.map((item) => ({
      key: item.mediaId,
      uri: '',
      caption: item.caption ?? '',
      status: 'ready',
      percent: 100,
      mediaId: item.mediaId,
    })),
    mode: post.status === 'PUBLISHED' ? 'PUBLISH' : post.status === 'SCHEDULED' ? 'SCHEDULE' : post.status === 'DRAFT' ? 'DRAFT' : 'PUBLISH',
    schedule: null,
    confirmNoUnconsented: false,
  };
}

export type ComposerAction =
  | { type: 'body'; value: string }
  | { type: 'comments'; value: boolean }
  | { type: 'postType'; value: PostType }
  | { type: 'toggleStudent'; studentId: string }
  | { type: 'setStudents'; studentIds: string[] }
  | { type: 'mode'; value: PublishMode }
  | { type: 'schedule'; value: SchedulePick | null }
  | { type: 'confirm'; value: boolean }
  | { type: 'attach'; key: string; uri: string }
  | { type: 'attachStatus'; key: string; status: AttachmentStatus }
  | { type: 'attachProgress'; key: string; percent: number }
  | { type: 'attachReady'; key: string; mediaId: string }
  | { type: 'attachFailed'; key: string; message: string }
  | { type: 'attachRetry'; key: string }
  | { type: 'caption'; key: string; value: string }
  | { type: 'detach'; key: string }
  | { type: 'move'; key: string; direction: -1 | 1 };

export function composerReducer(state: ComposerState, action: ComposerAction): ComposerState {
  switch (action.type) {
    case 'body':
      return { ...state, body: action.value };
    case 'comments':
      return { ...state, commentsEnabled: action.value };
    case 'postType':
      return { ...state, postType: action.value };
    case 'toggleStudent': {
      const has = state.studentIds.includes(action.studentId);
      const studentIds = has ? state.studentIds.filter((id) => id !== action.studentId) : [...state.studentIds, action.studentId].slice(0, MAX_TAGS);
      // The confirmation is about the photos and the tags as they were: a change asks again (the API drops it too).
      return { ...state, studentIds, confirmNoUnconsented: false };
    }
    case 'setStudents':
      return { ...state, studentIds: [...new Set(action.studentIds)].slice(0, MAX_TAGS), confirmNoUnconsented: false };
    case 'mode':
      return { ...state, mode: action.value };
    case 'schedule':
      return { ...state, schedule: action.value };
    case 'confirm':
      return { ...state, confirmNoUnconsented: action.value };
    case 'attach':
      if (state.attachments.some((item) => item.key === action.key) || state.attachments.length >= MAX_PHOTOS) return state;
      return {
        ...state,
        confirmNoUnconsented: false,
        attachments: [...state.attachments, { key: action.key, uri: action.uri, caption: '', status: 'preparing', percent: 0 }],
      };
    case 'attachStatus':
      return updateAttachment(state, action.key, (item) => ({ ...item, status: action.status, percent: action.status === 'uploading' ? 0 : item.percent }));
    case 'attachProgress':
      return updateAttachment(state, action.key, (item) => (item.status === 'uploading' ? { ...item, percent: clampPercent(action.percent) } : item));
    case 'attachReady':
      return updateAttachment(state, action.key, ({ failure: _failure, ...item }) => ({ ...item, status: 'ready', percent: 100, mediaId: action.mediaId }));
    case 'attachFailed':
      return updateAttachment(state, action.key, (item) => ({ ...item, status: 'failed', failure: action.message }));
    case 'attachRetry':
      return updateAttachment(state, action.key, ({ failure: _failure, ...item }) => ({ ...item, status: 'preparing', percent: 0 }));
    case 'caption':
      return updateAttachment(state, action.key, (item) => ({ ...item, caption: action.value.slice(0, MAX_CAPTION_CHARS) }));
    case 'detach':
      return { ...state, confirmNoUnconsented: false, attachments: state.attachments.filter((item) => item.key !== action.key) };
    case 'move': {
      const index = state.attachments.findIndex((item) => item.key === action.key);
      const target = index + action.direction;
      if (index < 0 || target < 0 || target >= state.attachments.length) return state;
      const attachments = [...state.attachments];
      [attachments[index], attachments[target]] = [attachments[target]!, attachments[index]!];
      return { ...state, attachments };
    }
  }
}

function updateAttachment(state: ComposerState, key: string, change: (item: Attachment) => Attachment): ComposerState {
  const current = state.attachments.find((item) => item.key === key);
  if (!current) return state;
  const changed = change(current);
  if (changed === current) return state;
  return { ...state, attachments: state.attachments.map((item) => (item.key === key ? changed : item)) };
}

function clampPercent(value: number): number {
  return Math.min(100, Math.max(0, Math.floor(value)));
}

// --- what can be sent -------------------------------------------------------------------------

export function readyPhotos(state: ComposerState): Attachment[] {
  return state.attachments.filter((item) => item.status === 'ready' && item.mediaId);
}

export function isUploading(state: ComposerState): boolean {
  return state.attachments.some((item) => item.status === 'preparing' || item.status === 'uploading');
}

export function hasFailedUpload(state: ComposerState): boolean {
  return state.attachments.some((item) => item.status === 'failed');
}

/** True when the photos need the author's explicit "no unconsented student appears" statement: photos and nobody tagged. */
export function needsConfirmation(state: ComposerState): boolean {
  return state.kind !== 'contribution' && readyPhotos(state).length > 0 && state.studentIds.length === 0;
}

export function charLimit(kind: ComposerKind): number {
  return kind === 'story' ? MAX_STORY_CHARS : MAX_POST_CHARS;
}

export type BlockedReason = 'uploading' | 'failed_upload' | 'empty' | 'too_long' | 'schedule_missing';

/** Why the send button waits, or null. Light checks only; the API validates everything again. */
export function blockedReason(state: ComposerState): BlockedReason | null {
  if (isUploading(state)) return 'uploading';
  if (hasFailedUpload(state)) return 'failed_upload';
  const text = state.body.trim();
  if (state.body.length > charLimit(state.kind)) return 'too_long';
  if (!text && readyPhotos(state).length === 0) return 'empty';
  if (state.kind === 'post' && state.mode === 'SCHEDULE' && !state.schedule) return 'schedule_missing';
  return null;
}

type CreatePost = S['PostCreateRequest'];
type UpdatePost = S['PostUpdateRequest'];
type CreateContribution = S['ContributionCreateRequest'];
type CreateStory = S['StoryCreateRequest'];

function media(state: ComposerState): { mediaId: string; caption?: string }[] {
  return readyPhotos(state).map((item) => {
    const caption = item.caption.trim();
    return caption ? { mediaId: item.mediaId!, caption } : { mediaId: item.mediaId! };
  });
}

function confirmFlag(state: ComposerState): { confirmNoUnconsentedStudents?: boolean } {
  return needsConfirmation(state) && state.confirmNoUnconsented ? { confirmNoUnconsentedStudents: true } : {};
}

function text(state: ComposerState): string {
  return state.body.trim();
}

/** `POST .../classes/{c}/posts` or `POST .../posts` (school). */
export function toCreatePost(state: ComposerState, languageCode: string, scheduledFor?: string): CreatePost {
  return {
    body: text(state),
    bodyLanguageCode: languageCode,
    postType: state.postType,
    media: media(state),
    studentIds: state.studentIds,
    commentsEnabled: state.commentsEnabled,
    mode: state.mode,
    ...(state.mode === 'SCHEDULE' && scheduledFor ? { scheduledFor } : {}),
    ...confirmFlag(state),
  };
}

/** `PATCH .../posts/{id}`: media and tags replace the whole list. */
export function toUpdatePost(state: ComposerState, languageCode: string): UpdatePost {
  return {
    body: text(state),
    bodyLanguageCode: languageCode,
    postType: state.postType,
    media: media(state),
    studentIds: state.studentIds,
    commentsEnabled: state.commentsEnabled,
    ...confirmFlag(state),
  };
}

/** `POST .../classes/{c}/contributions`: the contributor can never give the confirmation. */
export function toContribution(state: ComposerState, languageCode: string): CreateContribution {
  return { body: text(state), bodyLanguageCode: languageCode, media: media(state), studentIds: state.studentIds };
}

/** `POST .../stories`: one photo and/or a caption. */
export function toCreateStory(state: ComposerState, languageCode: string): CreateStory {
  const photo = readyPhotos(state)[0];
  const caption = text(state);
  return {
    ...(caption ? { caption, captionLanguageCode: languageCode } : {}),
    ...(photo?.mediaId ? { mediaId: photo.mediaId } : {}),
    studentIds: state.studentIds,
    ...confirmFlag(state),
  };
}

// --- idempotency ------------------------------------------------------------------------------

/**
 * A create call carries an `Idempotency-Key`. A retry of the very same request after the connection dropped (the server may
 * have created the post) must reuse the key; any change to the content, and any answer the server gave (a 422 created
 * nothing), starts a new one.
 */
export interface SubmitKey {
  key: string;
  fingerprint: string;
}

export function keyFor(previous: SubmitKey | null, payload: unknown, newKey: () => string): SubmitKey {
  const fingerprint = JSON.stringify(payload);
  return previous && previous.fingerprint === fingerprint ? previous : { key: newKey(), fingerprint };
}

/** The key to keep after a failed attempt: only a failure without an answer (offline, timeout, 5xx) keeps it. */
export function keyAfterFailure(key: SubmitKey, status: number | undefined): SubmitKey | null {
  return status === undefined || status >= 500 ? key : null;
}
