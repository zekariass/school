import type { SchedulePick } from '@/lib/feed/schedule';

import { allowedTypes, audienceProblem, mayRequireAcknowledgement, optionalChannels, toAudiences, type AudienceChoice, type AudienceProblem } from './audience';
import type {
  AnnouncementCapabilities,
  AnnouncementPreview,
  AnnouncementRequest,
  AnnouncementType,
  AnnouncementUpdateRequest,
  AnnouncementView,
  Channel,
  PublishMode,
} from './types';
import { isUrgentType } from './types';
import { choiceFromAudiences, defaultChoice } from './audience';

export const MAX_TITLE_CHARS = 200;
export const MAX_BODY_CHARS = 5000;
export const MAX_REASON_CHARS = 500;

type Caps = AnnouncementCapabilities;

/**
 * The composer's state. It lives in the screen's memory only: nothing about a draft is written to the phone (the server keeps a
 * draft only when the author saves one). The text is plain text and is sent as typed (trimmed).
 */
export interface ComposerState {
  type: AnnouncementType;
  title: string;
  body: string;
  requiresAcknowledgement: boolean;
  /** The optional channels chosen (push, email, SMS); in-app is always on and is added when sending. */
  channels: Channel[];
  audience: AudienceChoice;
  mode: PublishMode;
  schedule: SchedulePick | null;
}

export function initialComposer(caps: Caps, classId?: string | null): ComposerState {
  return {
    type: 'NORMAL',
    title: '',
    body: '',
    requiresAcknowledgement: false,
    channels: ['PUSH'],
    audience: defaultChoice(caps, classId),
    mode: 'PUBLISH',
    schedule: null,
  };
}

/** The composer filled from a draft or a scheduled announcement; null when its audience needs the web (chosen people, a campus). */
export function composerFromAnnouncement(view: AnnouncementView, caps: Caps): ComposerState | null {
  const audience = choiceFromAudiences(view.audiences ?? []);
  if (!audience) return null;
  const chosen = view.channels ?? [];
  return {
    type: view.type,
    title: view.title,
    body: view.body ?? '',
    requiresAcknowledgement: view.requiresAcknowledgement,
    channels: optionalChannels(caps, view.type).filter((channel) => chosen.includes(channel)),
    audience,
    mode: view.status === 'SCHEDULED' ? 'SCHEDULE' : 'PUBLISH',
    schedule: null,
  };
}

export type ComposerAction =
  | { type: 'title'; value: string }
  | { type: 'body'; value: string }
  | { type: 'kind'; value: AnnouncementType }
  | { type: 'acknowledgement'; value: boolean }
  | { type: 'channel'; channel: Channel; on: boolean }
  | { type: 'audience'; value: AudienceChoice }
  | { type: 'mode'; value: PublishMode }
  | { type: 'schedule'; value: SchedulePick | null };

export function composerReducer(state: ComposerState, action: ComposerAction): ComposerState {
  switch (action.type) {
    case 'title':
      return { ...state, title: action.value };
    case 'body':
      return { ...state, body: action.value };
    case 'kind':
      // An acknowledgement can be asked above NORMAL only; a more serious type asks for one by default.
      return {
        ...state,
        type: action.value,
        requiresAcknowledgement: mayRequireAcknowledgement(action.value) ? (state.type === 'NORMAL' ? isUrgentType(action.value) : state.requiresAcknowledgement) : false,
      };
    case 'acknowledgement':
      return { ...state, requiresAcknowledgement: mayRequireAcknowledgement(state.type) && action.value };
    case 'channel':
      return { ...state, channels: action.on ? [...new Set([...state.channels, action.channel])] : state.channels.filter((channel) => channel !== action.channel) };
    case 'audience':
      return { ...state, audience: action.value };
    case 'mode':
      return { ...state, mode: action.value };
    case 'schedule':
      return { ...state, schedule: action.value };
  }
}

// --- what can be sent -----------------------------------------------------------------------------

export type BlockedReason =
  | 'title_empty'
  | 'title_too_long'
  | 'body_empty'
  | 'body_too_long'
  | 'type_not_allowed'
  | 'schedule_missing'
  | `audience_${AudienceProblem}`;

/** Why the send button waits, or null. Light checks only; the API validates everything again. */
export function blockedReason(state: ComposerState, caps: Caps): BlockedReason | null {
  if (!state.title.trim()) return 'title_empty';
  if (state.title.length > MAX_TITLE_CHARS) return 'title_too_long';
  if (!state.body.trim()) return 'body_empty';
  if (state.body.length > MAX_BODY_CHARS) return 'body_too_long';
  if (!allowedTypes(caps).includes(state.type)) return 'type_not_allowed';
  const audience = audienceProblem(state.audience, caps);
  if (audience) return `audience_${audience}`;
  if (state.mode === 'SCHEDULE' && !state.schedule) return 'schedule_missing';
  return null;
}

/** Whether leaving would lose something: anything typed or chosen differs from how the composer opened. */
export function isDirty(initial: ComposerState, now: ComposerState): boolean {
  if (initial === now) return false;
  return (
    now.title.trim() !== initial.title.trim() ||
    now.body.trim() !== initial.body.trim() ||
    now.type !== initial.type ||
    now.requiresAcknowledgement !== initial.requiresAcknowledgement ||
    now.channels.slice().sort().join() !== initial.channels.slice().sort().join() ||
    JSON.stringify(now.audience) !== JSON.stringify(initial.audience) ||
    now.mode !== initial.mode ||
    JSON.stringify(now.schedule) !== JSON.stringify(initial.schedule)
  );
}

/** The channels of the request: in-app always, then the optional ones this type may use at this school. */
export function requestChannels(state: ComposerState, caps: Caps): Channel[] {
  const offered = optionalChannels(caps, state.type);
  return ['IN_APP', ...offered.filter((channel) => state.channels.includes(channel))];
}

/**
 * `POST .../announcements`. A draft first: publishing and scheduling go through `/publish` and `/schedule` once the author has
 * seen how many people it reaches, so the create call always asks for DRAFT.
 */
export function toDraftRequest(state: ComposerState, caps: Caps, languageCode: string): AnnouncementRequest {
  return {
    title: state.title.trim(),
    body: state.body.trim(),
    bodyLanguageCode: languageCode,
    type: state.type,
    requiresAcknowledgement: state.requiresAcknowledgement && mayRequireAcknowledgement(state.type),
    channels: requestChannels(state, caps),
    audiences: toAudiences(state.audience),
    mode: 'DRAFT',
  };
}

/** `PATCH` of a draft or scheduled announcement (everything may change before publishing). */
export function toDraftUpdate(state: ComposerState, caps: Caps, languageCode: string): AnnouncementUpdateRequest {
  const { mode: _mode, ...rest } = toDraftRequest(state, caps, languageCode);
  return rest;
}

/** The edit of a published announcement: title and text only, and the editor must say whether it matters (`material`). */
export interface PublishedEdit {
  title: string;
  body: string;
  /** `null` until the editor chooses: the send button waits. */
  material: boolean | null;
}

export function editFromView(view: Pick<AnnouncementView, 'title' | 'body'>): PublishedEdit {
  return { title: view.title, body: view.body ?? '', material: null };
}

export function editChanged(initial: PublishedEdit, now: PublishedEdit): boolean {
  return now.title.trim() !== initial.title.trim() || now.body.trim() !== initial.body.trim();
}

export function editBlockedReason(initial: PublishedEdit, now: PublishedEdit): 'title_empty' | 'title_too_long' | 'body_empty' | 'body_too_long' | 'unchanged' | 'material_missing' | null {
  if (!now.title.trim()) return 'title_empty';
  if (now.title.length > MAX_TITLE_CHARS) return 'title_too_long';
  if (!now.body.trim()) return 'body_empty';
  if (now.body.length > MAX_BODY_CHARS) return 'body_too_long';
  if (!editChanged(initial, now)) return 'unchanged';
  if (now.material === null) return 'material_missing';
  return null;
}

/** `PATCH` of a published announcement: `material` is required (400 `announcement.material_required` without it). */
export function toMaterialEdit(edit: PublishedEdit): AnnouncementUpdateRequest | null {
  if (edit.material === null) return null;
  return { title: edit.title.trim(), body: edit.body.trim(), material: edit.material };
}

// --- the confirmation before sending ---------------------------------------------------------------

export interface Confirmation {
  total: number;
  /** Non-zero parts of the audience, in a fixed order. */
  parts: { role: 'GUARDIAN' | 'STUDENT' | 'STAFF'; count: number }[];
  /** Urgent and safety announcements ignore quiet hours and muted types; the person must be told. */
  urgent: boolean;
  requiresAcknowledgement: boolean;
  scheduled: boolean;
}

export function confirmationFor(preview: AnnouncementPreview, state: Pick<ComposerState, 'type' | 'requiresAcknowledgement' | 'mode'>): Confirmation {
  const parts = [
    { role: 'GUARDIAN' as const, count: preview.guardians },
    { role: 'STUDENT' as const, count: preview.students },
    { role: 'STAFF' as const, count: preview.staff },
  ].filter((part) => part.count > 0);
  return {
    total: preview.recipients,
    parts,
    urgent: isUrgentType(state.type),
    requiresAcknowledgement: state.requiresAcknowledgement,
    scheduled: state.mode === 'SCHEDULE',
  };
}

// --- idempotency of the create call ----------------------------------------------------------------

export { keyAfterFailure, keyFor, type SubmitKey } from '@/lib/feed/composer';

// --- the language of the text (D42) -----------------------------------------------------------------------------------

/**
 * The languages an announcement can be written in: those of the school's country (`country.languageCodes`), plus the
 * language a saved announcement already has even when the country no longer lists it.
 */
export function contentLanguages(countryLanguages: readonly string[], current: string | null | undefined): string[] {
  return current && !countryLanguages.includes(current) ? [current, ...countryLanguages] : [...countryLanguages];
}

/**
 * The language a text starts in: the saved one, else the app's language when the country uses it, else the country's
 * default language.
 */
export function initialContentLanguage(input: {
  saved: string | null | undefined;
  uiLocale: string;
  countryLanguages: readonly string[];
  defaultLanguage: string;
}): string {
  if (input.saved) return input.saved;
  return input.countryLanguages.includes(input.uiLocale) ? input.uiLocale : input.defaultLanguage;
}
