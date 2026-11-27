import {
  anyFailed,
  anyUploading,
  isAttachmentAction,
  readyAttachments,
  reduceAttachments,
  type Attachment,
  type AttachmentAction,
} from '@/lib/media/attachments';

import { LIMITS, takesPhotos } from './model';
import type { ItemRequest, ItemUpdateRequest, PortfolioItem, PortfolioItemType, PortfolioVisibility, QuickCaptureRequest } from './types';

export { keyAfterFailure, keyFor, type SubmitKey } from '@/lib/feed/composer';

/** One item of a portfolio takes up to ten files (the API checks it again). */
export const MAX_FILES = 10;
export const MAX_TAGS = 20;

/** Who is writing: a student for themself (new or edited), or a teacher capturing for a student of the class. */
export type ComposerKind = 'student' | 'capture';

export interface ComposerState {
  kind: ComposerKind;
  itemType: PortfolioItemType;
  title: string;
  description: string;
  reflection: string;
  textContent: string;
  classId: string | null;
  subjectId: string | null;
  visibility: PortfolioVisibility;
  /** "Other children can be seen in this" (a capture that says nothing counts as yes on the server). */
  showsOtherChildren: boolean;
  /** Children who appear (a teacher's capture only: a student has no directory). */
  studentIds: string[];
  attachments: Attachment[];
  /** "I confirm that no child without permission appears": only for files with nobody tagged. */
  confirm: boolean;
}

export interface InitialComposer {
  kind: ComposerKind;
  itemType: PortfolioItemType;
  visibility: PortfolioVisibility;
  classId?: string | null;
  subjectId?: string | null;
}

export function initialComposer({ kind, itemType, visibility, classId = null, subjectId = null }: InitialComposer): ComposerState {
  return {
    kind,
    itemType,
    title: '',
    description: '',
    reflection: '',
    textContent: '',
    classId,
    subjectId,
    visibility,
    // A teacher's capture counts other children as present unless told otherwise; a child's own work starts as "only me".
    showsOtherChildren: kind === 'capture',
    studentIds: [],
    attachments: [],
    confirm: false,
  };
}

/** The composer filled from an item being edited (after "changes requested", or a draft). Files come back as ready, with no preview. */
export function composerFromItem(item: PortfolioItem): ComposerState {
  return {
    kind: 'student',
    itemType: item.itemType,
    title: item.title ?? '',
    description: item.description ?? '',
    reflection: item.reflection ?? '',
    textContent: item.textContent ?? '',
    classId: item.classId ?? null,
    subjectId: item.subjectId ?? null,
    visibility: item.visibility,
    showsOtherChildren: item.showsOtherChildren ?? false,
    studentIds: (item.taggedStudents ?? []).map((tag) => tag.studentId),
    attachments: item.media.map((file) => ({ key: file.mediaId, uri: '', caption: '', status: 'ready' as const, percent: 100, mediaId: file.mediaId })),
    confirm: item.mediaConfirmed ?? false,
  };
}

export type ComposerAction =
  | AttachmentAction
  | { type: 'itemType'; value: PortfolioItemType }
  | { type: 'title'; value: string }
  | { type: 'description'; value: string }
  | { type: 'reflection'; value: string }
  | { type: 'text'; value: string }
  | { type: 'class'; classId: string | null; subjectId: string | null }
  | { type: 'visibility'; value: PortfolioVisibility }
  | { type: 'showsOthers'; value: boolean }
  | { type: 'toggleStudent'; studentId: string }
  | { type: 'setStudents'; studentIds: string[] }
  | { type: 'confirm'; value: boolean };

export function composerReducer(state: ComposerState, action: ComposerAction): ComposerState {
  if (isAttachmentAction(action)) {
    const attachments = reduceAttachments(state.attachments, action, MAX_FILES);
    if (attachments === state.attachments) return state;
    // The confirmation is about the files as they were: adding or removing one asks again (the API drops it too).
    const filesChanged = action.type === 'attach' || action.type === 'detach';
    return { ...state, attachments, ...(filesChanged ? { confirm: false } : {}) };
  }
  switch (action.type) {
    case 'itemType': {
      if (action.value === state.itemType) return state;
      // Pictures stay when the new kind takes pictures too (a photo that is really a drawing); otherwise they go.
      const keep = takesPhotos(action.value) && takesPhotos(state.itemType);
      return { ...state, itemType: action.value, attachments: keep ? state.attachments : [], studentIds: keep ? state.studentIds : [], confirm: false };
    }
    case 'title':
      return { ...state, title: action.value };
    case 'description':
      return { ...state, description: action.value };
    case 'reflection':
      return { ...state, reflection: action.value };
    case 'text':
      return { ...state, textContent: action.value };
    case 'class':
      return { ...state, classId: action.classId, subjectId: action.subjectId };
    case 'visibility':
      return { ...state, visibility: action.value };
    case 'showsOthers':
      return { ...state, showsOtherChildren: action.value };
    case 'toggleStudent': {
      const has = state.studentIds.includes(action.studentId);
      const studentIds = has ? state.studentIds.filter((id) => id !== action.studentId) : [...state.studentIds, action.studentId].slice(0, MAX_TAGS);
      return { ...state, studentIds, confirm: false };
    }
    case 'setStudents':
      return { ...state, studentIds: [...new Set(action.studentIds)].slice(0, MAX_TAGS), confirm: false };
    case 'confirm':
      return { ...state, confirm: action.value };
  }
}

// --- what can be sent -------------------------------------------------------------------------

/** True when the files need the creator's explicit statement: files, and nobody tagged. */
export function needsConfirmation(state: ComposerState): boolean {
  return takesPhotos(state.itemType) && readyAttachments(state.attachments).length > 0 && state.studentIds.length === 0;
}

export type BlockedReason = 'uploading' | 'failed_upload' | 'title_missing' | 'too_long' | 'text_missing' | 'photo_missing';

/**
 * Why a button waits, or null. `draft` only needs a title; submitting (or a teacher's capture, which is approved at once) needs
 * the work itself: text for a text item, at least one picture for the others. Light checks only; the API validates again.
 */
export function blockedReason(state: ComposerState, intent: 'draft' | 'submit'): BlockedReason | null {
  if (anyUploading(state.attachments)) return 'uploading';
  if (anyFailed(state.attachments)) return 'failed_upload';
  if (!state.title.trim()) return 'title_missing';
  if (overLimit(state)) return 'too_long';
  if (intent === 'submit') {
    if (state.itemType === 'TEXT' && !state.textContent.trim()) return 'text_missing';
    if (takesPhotos(state.itemType) && readyAttachments(state.attachments).length === 0) return 'photo_missing';
  }
  return null;
}

const length = (text: string) => [...text.trim()].length;

/** The first field that is over its limit, so the screen can name it. */
export function overLimitField(state: ComposerState): 'title' | 'description' | 'reflection' | 'text' | null {
  if (length(state.title) > LIMITS.title) return 'title';
  if (length(state.description) > LIMITS.description) return 'description';
  if (length(state.reflection) > LIMITS.reflection) return 'reflection';
  if (state.itemType === 'TEXT' && length(state.textContent) > LIMITS.text) return 'text';
  return null;
}

const overLimit = (state: ComposerState) => overLimitField(state) !== null;

/**
 * Whether leaving would lose something: the words, the pictures, the tags or the audience differ from how the composer
 * opened (an untouched screen closes without asking).
 */
export function isDirty(initial: ComposerState, now: ComposerState): boolean {
  if (initial === now) return false;
  const files = (state: ComposerState) => state.attachments.map((item) => item.key).join('|');
  return (
    now.title.trim() !== initial.title.trim() ||
    now.description.trim() !== initial.description.trim() ||
    now.reflection.trim() !== initial.reflection.trim() ||
    now.textContent.trim() !== initial.textContent.trim() ||
    now.classId !== initial.classId ||
    now.visibility !== initial.visibility ||
    now.showsOtherChildren !== initial.showsOtherChildren ||
    now.studentIds.join(',') !== initial.studentIds.join(',') ||
    files(now) !== files(initial)
  );
}

function fields(state: ComposerState) {
  const description = state.description.trim();
  const reflection = state.reflection.trim();
  const text = state.textContent.trim();
  return {
    title: state.title.trim(),
    ...(description ? { description } : {}),
    ...(reflection ? { reflection } : {}),
    ...(state.itemType === 'TEXT' && text ? { textContent: text } : {}),
    ...(state.subjectId ? { subjectId: state.subjectId } : {}),
    visibility: state.visibility,
    ...(takesPhotos(state.itemType) ? { mediaIds: mediaIds(state), showsOtherChildren: state.showsOtherChildren } : {}),
    ...(needsConfirmation(state) && state.confirm ? { confirmNoUnconsentedStudents: true } : {}),
  };
}

function mediaIds(state: ComposerState): string[] {
  return readyAttachments(state.attachments).map((item) => item.mediaId!);
}

/** `POST .../portfolio/items`: a student's own work, saved as a draft or submitted at once. */
export function toCreateRequest(state: ComposerState, mode: 'DRAFT' | 'SUBMIT'): ItemRequest {
  return { itemType: state.itemType, ...fields(state), ...(state.classId ? { classId: state.classId } : {}), mode };
}

/** `POST .../classes/{c}/portfolio/quick-capture`: a teacher's capture for a student of the class (approved at once). */
export function toQuickCapture(state: ComposerState, studentId: string): QuickCaptureRequest {
  return {
    itemType: state.itemType,
    studentId,
    ...fields(state),
    ...(state.studentIds.length > 0 ? { taggedStudentIds: state.studentIds } : {}),
  };
}

/**
 * `PATCH .../portfolio/items/{id}`: every editable field (an empty text clears it). The creator's confirmation is sent again
 * only when it is new or the files changed (the API drops it then).
 */
export function toUpdateRequest(state: ComposerState, initial: ComposerState, wasConfirmed: boolean): ItemUpdateRequest {
  const filesChanged = files(initial) !== files(state);
  const { confirmNoUnconsentedStudents: _confirm, ...rest } = fields(state);
  const confirm = needsConfirmation(state) && state.confirm && (!wasConfirmed || filesChanged);
  return {
    ...rest,
    description: state.description.trim(),
    reflection: state.reflection.trim(),
    ...(state.itemType === 'TEXT' ? { textContent: state.textContent.trim() } : {}),
    ...(state.classId ? { classId: state.classId } : {}),
    ...(confirm ? { confirmNoUnconsentedStudents: true } : {}),
  };
}

function files(state: ComposerState): string {
  return state.attachments.map((item) => item.mediaId ?? item.key).join('|');
}
