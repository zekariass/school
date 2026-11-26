import type { ConversationSummary } from "./types";

/** An observer reads but never writes (a guardian watching their child's conversation with an adult). */
export function isObserver(c: Pick<ConversationSummary, "myRole">): boolean {
  return c.myRole === "OBSERVER";
}

export type LastMessageState = "none" | "text" | "image" | "deleted" | "hidden";

/**
 * What the inbox row says about the last message. Deleted and hidden messages are tombstones: the API sends no text for
 * them and this never invents any. An image message has no preview text either.
 */
export function lastMessageState(c: Pick<ConversationSummary, "lastMessage">): LastMessageState {
  const last = c.lastMessage;
  if (!last) return "none";
  if (last.status === "DELETED") return "deleted";
  if (last.status === "HIDDEN") return "hidden";
  if (last.preview === undefined || last.preview === null) return last.messageType === "MEDIA" ? "image" : "none";
  return "text";
}

/** "3", "99+" or null for nothing to show. The API counts up to 100. */
export function unreadBadge(count: number): string | null {
  if (!Number.isFinite(count) || count <= 0) return null;
  return count > 99 ? "99+" : String(Math.floor(count));
}

/** True while a mute is in force (the API only sends `muted` while it is, this also guards against a stale clock skew). */
export function isMuted(c: Pick<ConversationSummary, "muted" | "mutedUntil">, now: Date): boolean {
  if (!c.muted) return false;
  if (!c.mutedUntil) return true;
  return Date.parse(c.mutedUntil) > now.getTime();
}

/** The sum of the unread counts the list shows (the header badge uses the API's own total, this one is for tabs). */
export function sumUnread(items: readonly Pick<ConversationSummary, "unreadCount">[]): number {
  return items.reduce((sum, c) => sum + Math.max(0, c.unreadCount), 0);
}

export interface ChildGroup {
  /** The child's id, or null for conversations that are about nobody in particular (groups, staff). */
  studentId: string | null;
  childName: string | null;
  items: ConversationSummary[];
}

/**
 * A guardian's inbox grouped by child: the API says which child a conversation is about (`contextStudent`) or whom the
 * guardian acts for (`onBehalfOfStudentId`). Groups keep the order of their newest conversation, the group without a child comes last.
 */
export function groupByChild(items: readonly ConversationSummary[]): ChildGroup[] {
  const groups = new Map<string, ChildGroup>();
  const none: ChildGroup = { studentId: null, childName: null, items: [] };
  for (const c of items) {
    const id = c.contextStudent?.studentId ?? c.onBehalfOfStudentId ?? null;
    if (!id) {
      none.items.push(c);
      continue;
    }
    let group = groups.get(id);
    if (!group) {
      group = { studentId: id, childName: c.contextStudent?.displayName ?? null, items: [] };
      groups.set(id, group);
    }
    group.childName ??= c.contextStudent?.displayName ?? null;
    group.items.push(c);
  }
  return none.items.length > 0 ? [...groups.values(), none] : [...groups.values()];
}

/** Grouping by child is for guardians who act for more than one conversation context: any conversation with a child makes sense of it. */
export function shouldGroupByChild(items: readonly ConversationSummary[]): boolean {
  const children = new Set<string>();
  for (const c of items) {
    const id = c.contextStudent?.studentId ?? c.onBehalfOfStudentId;
    if (id && c.myActingAs === "GUARDIAN") children.add(id);
  }
  return children.size > 0;
}

/** Merges inbox pages (newest activity first) without duplicates; the first occurrence wins (it is the fresher page). */
export function flattenInbox(pages: readonly { items: readonly ConversationSummary[] }[]): ConversationSummary[] {
  const seen = new Set<string>();
  const out: ConversationSummary[] = [];
  for (const page of pages) {
    for (const c of page.items) {
      if (seen.has(c.id)) continue;
      seen.add(c.id);
      out.push(c);
    }
  }
  return out;
}
