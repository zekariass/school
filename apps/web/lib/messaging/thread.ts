import type { ConversationSummary, Message } from "./types";

/**
 * The messages of a conversation in reading order (oldest first). The API pages newest first with a keyset cursor;
 * pages are fetched one after the other and a message can appear in two of them when something was sent in between,
 * so the first occurrence (the fresher page) wins.
 */
export function flattenMessages(pages: readonly { items: readonly Message[] }[]): Message[] {
  const seen = new Set<string>();
  const rows: { message: Message; index: number }[] = [];
  let index = 0;
  for (const page of pages) {
    for (const message of page.items) {
      index++;
      if (seen.has(message.id)) continue;
      seen.add(message.id);
      rows.push({ message, index });
    }
  }
  // Oldest first. Equal instants keep the API's order (newest first in the input, so the larger index is older).
  rows.sort((a, b) => {
    const at = Date.parse(a.message.sentAt ?? "") || 0;
    const bt = Date.parse(b.message.sentAt ?? "") || 0;
    return at !== bt ? at - bt : b.index - a.index;
  });
  return rows.map((r) => r.message);
}

export interface Paged<T> {
  items: T[];
  nextCursor?: string;
}

/**
 * Puts a message the caller just sent (or an edited, reacted-to or deleted one the API returned) into the cached pages:
 * replaces the message with the same id, otherwise adds it to the newest page. Never adds a message twice.
 */
export function upsertMessage<P extends Paged<Message>>(pages: readonly P[], message: Message): P[] {
  if (pages.length === 0) return [];
  let found = false;
  const next = pages.map((page) => ({
    ...page,
    items: page.items.map((m) => {
      if (m.id !== message.id) return m;
      found = true;
      return message;
    }),
  }));
  if (found) return next;
  const [first, ...rest] = next as [P, ...P[]];
  return [{ ...first, items: [message, ...first.items] }, ...rest];
}

/** Replaces a deleted message with a tombstone in place (the API removes text, images and reactions from it). */
export function tombstone(message: Message): Message {
  return { ...message, status: "DELETED", body: undefined, attachments: [], reactions: [], canEdit: false, canDelete: false };
}

export function isTombstone(message: Pick<Message, "status">): boolean {
  return message.status === "DELETED" || message.status === "HIDDEN";
}

/** A system message (the API wrote it, nobody typed it). */
export function isSystem(message: Pick<Message, "messageType">): boolean {
  return message.messageType === "SYSTEM";
}

/** The id of the newest message from somebody else (what "mark as read" moves the watermark to), or null. */
export function newestForeignId(messages: readonly Message[]): string | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!;
    if (!m.mine && !isTombstone(m) && m.status !== "SCHEDULED") return m.id;
  }
  return null;
}

/**
 * The newest own message of a DIRECT conversation that carries a read receipt: the only one the UI marks "Seen"
 * (the API sets `seenAt` only there; the UI never shows it anywhere else).
 */
export function lastSeenId(messages: readonly Message[], direct: boolean): string | null {
  if (!direct) return null;
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!;
    if (m.mine && m.seenAt) return m.id;
  }
  return null;
}

/** A day key ("2026-11-26") on the wall clock of `timeZone`, to put a date line between two days. */
export function dayKey(instant: string, timeZone: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return "";
  try {
    return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
  } catch {
    return date.toISOString().slice(0, 10);
  }
}

export type ThreadRow =
  | { kind: "day"; key: string; instant: string }
  | { kind: "message"; key: string; message: Message; /** The previous row is the same sender within a few minutes: no repeated name. */ grouped: boolean };

const GROUP_WINDOW_MS = 5 * 60_000;

/** Messages with a date line before the first message of every day. */
export function threadRows(messages: readonly Message[], timeZone: string): ThreadRow[] {
  const rows: ThreadRow[] = [];
  let lastDay = "";
  let previous: Message | null = null;
  for (const message of messages) {
    const instant = message.sentAt ?? "";
    const day = instant ? dayKey(instant, timeZone) : "";
    if (day && day !== lastDay) {
      rows.push({ kind: "day", key: `day-${day}`, instant });
      lastDay = day;
      previous = null;
    }
    const grouped =
      previous !== null &&
      !isSystem(message) &&
      !isSystem(previous) &&
      sameSender(previous, message) &&
      Math.abs((Date.parse(instant) || 0) - (Date.parse(previous.sentAt ?? "") || 0)) < GROUP_WINDOW_MS;
    rows.push({ kind: "message", key: message.id, message, grouped });
    previous = message;
  }
  return rows;
}

function sameSender(a: Message, b: Message): boolean {
  if (a.mine !== b.mine) return false;
  if (a.mine) return true;
  return !!a.sender?.memberId && a.sender.memberId === b.sender?.memberId;
}

/** Looks up the message a reply points at, among the loaded ones (replies carry ids only, never quoted text). */
export function findReplyTarget(messages: readonly Message[], id: string | undefined): Message | null {
  if (!id) return null;
  return messages.find((m) => m.id === id) ?? null;
}

// --- what the conversation lets the viewer do ---------------------------------------------------------------------

export type ReadOnlyReason = "observer" | "closed" | "restricted" | null;

/**
 * Why the composer is off, from what the API says (`canPost`, `myRole`, `status`). There is no policy here: the API decides
 * `canPost`; this only picks the explanation. The composer is off for observers and for closed conversations in any case.
 */
export function readOnlyReason(c: Pick<ConversationSummary, "canPost" | "myRole" | "status" | "postingPolicy">): ReadOnlyReason {
  if (c.myRole === "OBSERVER") return "observer";
  if (c.status !== "ACTIVE") return "closed";
  if (!c.canPost) return "restricted";
  return null;
}

export type Banner =
  | { kind: "observerGuardian" }
  | { kind: "studentGuardiansCanSee" }
  | { kind: "studentReviewable" }
  | { kind: "adultStudentNotPrivate" }
  | { kind: "closed"; status: string }
  | { kind: "restricted" }
  | { kind: "pendingApproval" };

/**
 * The notices at the top of a conversation, in order. The safeguarding notices are not optional: a student is told who can
 * read their conversation with an adult, the adult is told it is not private, an observing guardian is told they only read.
 */
export function banners(c: Pick<ConversationSummary, "canPost" | "myRole" | "status" | "postingPolicy" | "myActingAs" | "directKind" | "guardiansCanSee" | "type">): Banner[] {
  const out: Banner[] = [];
  const adultStudent = c.type === "DIRECT" && c.directKind === "TEACHER_STUDENT";
  if (c.myRole === "OBSERVER") out.push({ kind: "observerGuardian" });
  else if (adultStudent && c.myActingAs === "STUDENT") {
    out.push(c.guardiansCanSee === false ? { kind: "studentReviewable" } : { kind: "studentGuardiansCanSee" });
  } else if (adultStudent) out.push({ kind: "adultStudentNotPrivate" });
  if (c.status === "PENDING_APPROVAL") out.push({ kind: "pendingApproval" });
  else if (c.status !== "ACTIVE") out.push({ kind: "closed", status: c.status });
  else if (c.myRole !== "OBSERVER" && !c.canPost) out.push({ kind: "restricted" });
  return out;
}

/**
 * A conversation is a direct one between two non-observers: the only place a "Seen" receipt exists.
 */
export function hasReceipts(c: Pick<ConversationSummary, "type" | "myRole">): boolean {
  return c.type === "DIRECT" && c.myRole !== "OBSERVER";
}
