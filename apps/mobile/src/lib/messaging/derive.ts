/**
 * What the screens show, derived from the API's payloads. Pure (no React Native), so vitest covers it.
 *
 * Nothing here decides who may do what: the server does, on every request. These functions only choose which controls and notices
 * to show from the flags the API sent (`canPost`, `canEdit`, `canDelete`, `myRole`, `canModerate`, `guardiansCanSee`), and when in
 * doubt they choose the more protective reading (a tombstone never shows text, an unknown state is read-only).
 */

import type { ConversationSummary, GuardianLabel, MessageItem, Participant, RelationshipType } from './types';

// --- tombstones and text ----------------------------------------------------------------------------------------------

/** Deleted and hidden messages are tombstones: the API sends no body for them and the app never shows one. */
export function isTombstone(message: Pick<MessageItem, 'status'>): boolean {
  return message.status === 'DELETED' || message.status === 'HIDDEN';
}

/** The text to print for a message, or null for a tombstone and for a message without text (an image). */
export function messageText(message: Pick<MessageItem, 'status' | 'body'>): string | null {
  if (isTombstone(message)) return null;
  return message.body && message.body.length > 0 ? message.body : null;
}

/** Images of a message: none for a tombstone, whatever the payload says. */
export function messageImages(message: Pick<MessageItem, 'status' | 'attachments'>): { mediaId: string; variants: readonly string[] }[] {
  if (isTombstone(message)) return [];
  return [...message.attachments]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((attachment) => ({ mediaId: attachment.mediaId, variants: attachment.media?.variants ?? [] }));
}

export function isSystemMessage(message: Pick<MessageItem, 'messageType'>): boolean {
  return message.messageType === 'SYSTEM';
}

// --- who is writing ---------------------------------------------------------------------------------------------------

export type ParticipantLabel =
  | { kind: 'you' }
  | { kind: 'name'; name: string }
  /** "Abebe's mother": what the child's teachers see of a guardian. */
  | { kind: 'guardianOf'; relationship: RelationshipType; child: string }
  /** "Your mother": what a student sees of their own guardian. */
  | { kind: 'yourRelation'; relationship: RelationshipType }
  /** "Parent": what other guardians see; a student whose guardian label has no relationship sees "Your guardian". */
  | { kind: 'parent' }
  | { kind: 'yourGuardian' }
  | { kind: 'member' };

/**
 * How a person of a conversation is called. People are identified by `memberId`, never by user id; a guardian is never named,
 * only labelled, and what the label says depends on who looks (the API decides; this only maps it).
 */
export function participantLabel(person: Pick<Participant, 'self' | 'displayName' | 'actingAs' | 'guardian'> | undefined, viewer: ConversationSummary['myActingAs'] | undefined): ParticipantLabel {
  if (!person) return { kind: 'member' };
  if (person.self) return { kind: 'you' };
  if (person.actingAs === 'GUARDIAN') return guardianLabel(person.guardian, viewer);
  if (person.displayName) return { kind: 'name', name: person.displayName };
  return { kind: 'member' };
}

function guardianLabel(guardian: GuardianLabel | undefined, viewer: ConversationSummary['myActingAs'] | undefined): ParticipantLabel {
  if (guardian?.relationshipType && guardian.studentGivenName) {
    return { kind: 'guardianOf', relationship: guardian.relationshipType, child: guardian.studentGivenName };
  }
  if (viewer === 'STUDENT') return guardian?.relationshipType ? { kind: 'yourRelation', relationship: guardian.relationshipType } : { kind: 'yourGuardian' };
  return { kind: 'parent' };
}

// --- what the viewer may do here (flags only) -------------------------------------------------------------------------

export type AccessBanner = 'observer' | 'closed' | 'pending' | 'restricted';

export interface ConversationAccess {
  /** The composer is shown. Every send is still checked by the server. */
  canCompose: boolean;
  /** Why it is not: a read-only notice instead of the composer. */
  banner: AccessBanner | null;
}

/**
 * Observers (a guardian who can read a child's conversation with an adult) are strictly read-only: no composer, no reactions, no
 * edit. A conversation that is not ACTIVE is closed. Posting limited to moderators shows its own notice. Anything unexpected is
 * read-only.
 */
export function accessOf(summary: Pick<ConversationSummary, 'myRole' | 'status' | 'canPost'>): ConversationAccess {
  if (summary.myRole === 'OBSERVER') return { canCompose: false, banner: 'observer' };
  if (summary.status === 'PENDING_APPROVAL') return { canCompose: false, banner: 'pending' };
  if (summary.status !== 'ACTIVE') return { canCompose: false, banner: 'closed' };
  if (!summary.canPost) return { canCompose: false, banner: 'restricted' };
  return { canCompose: true, banner: null };
}

export function isObserving(summary: Pick<ConversationSummary, 'myRole'>): boolean {
  return summary.myRole === 'OBSERVER';
}

export type SafeguardingNotice = 'guardiansCanSee' | 'teacherStudent' | null;

/**
 * Notices about who can read an adult-student conversation. The student is told only when the API says a guardian can see it
 * (`guardiansCanSee`); the teacher is always reminded that such conversations are shared with the family and the school.
 */
export function safeguardingNotice(summary: Pick<ConversationSummary, 'myActingAs' | 'directKind' | 'guardiansCanSee'>): SafeguardingNotice {
  if (summary.myActingAs === 'STUDENT' && summary.guardiansCanSee === true) return 'guardiansCanSee';
  if (summary.directKind === 'TEACHER_STUDENT' && summary.myActingAs !== 'STUDENT') return 'teacherStudent';
  return null;
}

/** Whether the "Seen" line may be shown under a message: only the API's `seenAt` on one's own message. */
export function seenAtOf(message: Pick<MessageItem, 'mine' | 'seenAt' | 'status'>): string | null {
  return message.mine && message.seenAt && !isTombstone(message) ? message.seenAt : null;
}

// --- edit window ------------------------------------------------------------------------------------------------------

/** Milliseconds left to edit one's own message (0 when it can no longer be edited). The server enforces the window. */
export function editRemainingMs(message: Pick<MessageItem, 'canEdit' | 'sentAt' | 'status'>, windowMinutes: number, nowMs: number): number {
  if (!message.canEdit || isTombstone(message) || windowMinutes <= 0 || !message.sentAt) return 0;
  const sent = Date.parse(message.sentAt);
  if (Number.isNaN(sent)) return 0;
  return Math.max(0, sent + windowMinutes * 60_000 - nowMs);
}

/** Whole minutes to show next to "Edit" (rounded up), or null when editing is closed. */
export function editMinutesLeft(remainingMs: number): number | null {
  return remainingMs > 0 ? Math.max(1, Math.ceil(remainingMs / 60_000)) : null;
}

// --- the inbox --------------------------------------------------------------------------------------------------------

export type Preview = { kind: 'none' } | { kind: 'tombstone' } | { kind: 'image' } | { kind: 'text'; text: string };

/** The one-line preview of a row. Never a body for a deleted or hidden message (the API sends none; this is the second lock). */
export function previewOf(summary: Pick<ConversationSummary, 'lastMessage'>): Preview {
  const last = summary.lastMessage;
  if (!last) return { kind: 'none' };
  if (last.status === 'DELETED' || last.status === 'HIDDEN') return { kind: 'tombstone' };
  if (last.preview && last.preview.length > 0) return { kind: 'text', text: last.preview };
  if (last.messageType === 'MEDIA') return { kind: 'image' };
  return { kind: 'none' };
}

export type InboxTitle =
  | { kind: 'person'; person: Participant | undefined }
  | { kind: 'title'; title: string }
  | { kind: 'group'; groupKind: NonNullable<ConversationSummary['groupKind']> | 'GROUP' };

export function inboxTitle(summary: Pick<ConversationSummary, 'type' | 'counterpart' | 'title' | 'groupKind'>): InboxTitle {
  if (summary.type === 'DIRECT') return { kind: 'person', person: summary.counterpart };
  if (summary.title && summary.title.trim().length > 0) return { kind: 'title', title: summary.title };
  return { kind: 'group', groupKind: summary.groupKind ?? 'GROUP' };
}

export interface InboxSection {
  /** The child's id, or `none` for conversations that are not about a child. */
  key: string;
  childName: string | null;
  items: ConversationSummary[];
}

/**
 * A guardian's inbox grouped by child (`onBehalfOfStudentId` / `contextStudent`), in order of first appearance (the list is
 * already newest first). Conversations that are not about a child form the last section.
 */
export function groupByChild(items: readonly ConversationSummary[]): InboxSection[] {
  const sections = new Map<string, InboxSection>();
  for (const item of items) {
    const key = item.onBehalfOfStudentId ?? item.contextStudent?.studentId ?? 'none';
    const section = sections.get(key) ?? { key, childName: item.contextStudent?.displayName ?? null, items: [] };
    if (!section.childName && item.contextStudent?.displayName) section.childName = item.contextStudent.displayName;
    section.items.push(item);
    sections.set(key, section);
  }
  const all = [...sections.values()];
  return [...all.filter((section) => section.key !== 'none'), ...all.filter((section) => section.key === 'none')];
}

/** The tab badge: nothing at 0, "99+" past 99. */
export function badgeText(unread: number | undefined): string | undefined {
  if (!unread || unread <= 0) return undefined;
  return unread > 99 ? '99+' : String(unread);
}

/** Unread count of a row as shown (a muted conversation still counts, quietly; the API already excludes your own and blocked ones). */
export function unreadText(count: number): string | null {
  if (count <= 0) return null;
  return count > 99 ? '99+' : String(count);
}

// --- composing --------------------------------------------------------------------------------------------------------

export function sendable(text: string, imageCount: number, maxLength: number): boolean {
  const trimmed = text.trim();
  if (trimmed.length === 0 && imageCount === 0) return false;
  return text.length <= maxLength;
}

/** The message ids that are new in `next` and written by somebody else (for the screen reader's "new message" announcement). */
export function incomingFromOthers(previous: readonly Pick<MessageItem, 'id'>[], next: readonly MessageItem[]): MessageItem[] {
  const known = new Set(previous.map((message) => message.id));
  return next.filter((message) => !known.has(message.id) && !message.mine && !isTombstone(message) && !isSystemMessage(message));
}

/** The newest of one's own messages that the other person has seen: the only one that gets a "Seen" line (the rest would be clutter). */
export function lastSeenMessageId(messages: readonly Pick<MessageItem, 'id' | 'mine' | 'seenAt' | 'status'>[]): string | null {
  // `messages` is newest first.
  for (const message of messages) if (seenAtOf(message)) return message.id;
  return null;
}
