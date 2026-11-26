/** Pure helpers of the announcement screens: inbox queries, acknowledge state, badges, report and status labels. */

export type AnnouncementType = "NORMAL" | "IMPORTANT" | "URGENT" | "SAFETY";
export type AnnouncementStatus = "DRAFT" | "SCHEDULED" | "PUBLISHED" | "WITHDRAWN";

/** URGENT and SAFETY ignore quiet hours and mute settings. */
export function isUrgentType(type: AnnouncementType): boolean {
  return type === "URGENT" || type === "SAFETY";
}

/** An acknowledgement can be required above NORMAL only (the API answers 400 `announcement.ack_not_allowed` otherwise). */
export function mayRequireAcknowledgement(type: AnnouncementType): boolean {
  return type !== "NORMAL";
}

// --- text in a language ---------------------------------------------------------------------

/** The longest reason or edit note the API takes. */
export const NOTE_MAX = 500;

interface Translatable {
  title: string;
  body?: string | null;
  bodyLanguageCode?: string | null;
  translations?: Record<string, { title: string; body: string }> | null;
}

/** The languages an announcement can be read in: the one it was written in first, then the translations people added. */
export function languagesOf(a: Pick<Translatable, "bodyLanguageCode" | "translations">): string[] {
  const own = a.bodyLanguageCode ?? "und";
  const others = Object.keys(a.translations ?? {}).filter((l) => l !== own);
  return [own, ...others];
}

/** The language to open in: the reader's own when a person translated it into that language, else the original. */
export function defaultLanguage(a: Pick<Translatable, "bodyLanguageCode" | "translations">, uiLocale: string): string {
  const own = a.bodyLanguageCode ?? "und";
  return uiLocale !== own && a.translations && Object.hasOwn(a.translations, uiLocale) ? uiLocale : own;
}

/** Title and body in `language`; the original when there is no such translation (and never a machine's guess). */
export function textIn(a: Translatable, language: string): { title: string; body: string | null; language: string } {
  const own = a.bodyLanguageCode ?? "und";
  const translated = language !== own && a.translations && Object.hasOwn(a.translations, language) ? a.translations[language] : undefined;
  if (translated) return { title: translated.title, body: translated.body, language };
  return { title: a.title, body: a.body ?? null, language: own };
}

// --- inbox --------------------------------------------------------------------------------

export type InboxFilter = "ALL" | "UNREAD" | "NEEDS_ACKNOWLEDGEMENT";
export type InboxSort = "NEWEST" | "UNREAD_FIRST";

export const INBOX_FILTERS: readonly InboxFilter[] = ["ALL", "UNREAD", "NEEDS_ACKNOWLEDGEMENT"];
export const INBOX_SORTS: readonly InboxSort[] = ["NEWEST", "UNREAD_FIRST"];

export function isInboxFilter(value: unknown): value is InboxFilter {
  return typeof value === "string" && (INBOX_FILTERS as readonly string[]).includes(value);
}

export function isInboxSort(value: unknown): value is InboxSort {
  return typeof value === "string" && (INBOX_SORTS as readonly string[]).includes(value);
}

/** The query of `GET /me/announcements`: defaults (everything, newest first) are left out so the API sees only a choice. */
export function inboxQuery(options: { schoolId?: string | null; filter?: InboxFilter; sort?: InboxSort; cursor?: string; limit?: number }) {
  return {
    ...(options.schoolId ? { schoolId: options.schoolId } : {}),
    ...(options.filter && options.filter !== "ALL" ? { filter: options.filter } : {}),
    ...(options.sort && options.sort !== "NEWEST" ? { sort: options.sort } : {}),
    ...(options.cursor ? { cursor: options.cursor } : {}),
    ...(options.limit ? { limit: options.limit } : {}),
  };
}

/** What the header badge counts: the unread announcements (those waiting for an acknowledgement are announced separately). */
export function badgeCount(summary: { unread?: number; needingAcknowledgement?: number } | undefined): number {
  const n = summary?.unread ?? 0;
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

export function acknowledgementCount(summary: { needingAcknowledgement?: number } | undefined): number {
  const n = summary?.needingAcknowledgement ?? 0;
  return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0;
}

/** "9", "99+": a badge never shows more digits than it can hold. */
export function badgeText(count: number): string {
  return count > 99 ? "99+" : String(count);
}

/** True for a notification that opens an announcement: its list, badge and inbox are refreshed when one arrives. */
export function isAnnouncementNotification(data: unknown): boolean {
  if (typeof data !== "object" || data === null) return false;
  const path = (data as { path?: unknown }).path;
  return typeof path === "string" && (path === "/announcements" || path.startsWith("/announcements/"));
}

// --- acknowledge ----------------------------------------------------------------------------

export interface AckSubject {
  requiresAcknowledgement: boolean;
  status: AnnouncementStatus;
  revision: number;
  authorId?: string;
  mine?: { acknowledged?: boolean; acknowledgedRevision?: number | null; reacknowledgementNeeded?: boolean };
}

/**
 * - `none`: nothing to acknowledge (not asked for, withdrawn, a draft, or the viewer wrote it)
 * - `pending`: asked for and not yet done
 * - `again`: acknowledged an earlier revision; a material edit asks once more
 * - `done`: acknowledged the current revision
 */
export type AckState = "none" | "pending" | "again" | "done";

export function ackState(a: AckSubject, viewerId?: string): AckState {
  if (!a.requiresAcknowledgement || a.status !== "PUBLISHED") return "none";
  if (viewerId && a.authorId === viewerId) return "none";
  if (!a.mine?.acknowledged) return "pending";
  // The server decides: every edit after publishing raises `revision`, so comparing revisions would also ask again
  // after a silent correction. Only a material edit sets `reacknowledgementNeeded`.
  return a.mine.reacknowledgementNeeded ? "again" : "done";
}

/** True when the acknowledge button is shown (first time or again). */
export function canAcknowledge(a: AckSubject, viewerId?: string): boolean {
  const state = ackState(a, viewerId);
  return state === "pending" || state === "again";
}

/** A list card only knows whether the viewer acknowledged at all. */
export function cardNeedsAcknowledgement(card: { requiresAcknowledgement: boolean; status: AnnouncementStatus; acknowledgedAt?: string | null }): boolean {
  return card.requiresAcknowledgement && card.status === "PUBLISHED" && !card.acknowledgedAt;
}

/** "New": unread and the viewer is in the audience snapshot (late joiners who find it in the school list get no badge). */
export function isNewToViewer(a: { mine?: { read?: boolean; recipient?: boolean }; status: AnnouncementStatus }): boolean {
  return a.status === "PUBLISHED" && !!a.mine && a.mine.recipient !== false && !a.mine.read;
}

/** A card of the inbox (a snapshot list) is new while unread and not withdrawn. */
export function cardIsNew(card: { readAt?: string | null; status: AnnouncementStatus }): boolean {
  return card.status !== "WITHDRAWN" && !card.readAt;
}

/** A withdrawn announcement has no body: only the title, the time and the public note remain. */
export function isTombstone(a: { status: AnnouncementStatus }): boolean {
  return a.status === "WITHDRAWN";
}

// --- authored lists -----------------------------------------------------------------------

export type AuthoredTab = "ALL" | AnnouncementStatus;
export const AUTHORED_TABS: readonly AuthoredTab[] = ["ALL", "DRAFT", "SCHEDULED", "PUBLISHED", "WITHDRAWN"];

export function authoredStatus(tab: AuthoredTab): AnnouncementStatus | undefined {
  return tab === "ALL" ? undefined : tab;
}

export type SchoolTab = "school" | "mine" | "all";

/** The tabs of the school section: everybody reads the school list, authors see "Mine", managers also see everything written. */
export function schoolTabs(access: { canCompose: boolean; manager: boolean }): SchoolTab[] {
  const tabs: SchoolTab[] = ["school"];
  if (access.canCompose || access.manager) tabs.push("mine");
  if (access.manager) tabs.push("all");
  return tabs;
}

// --- report -------------------------------------------------------------------------------

export type RecipientFilter = "ALL" | "UNREAD" | "UNACKNOWLEDGED" | "ACKNOWLEDGED" | "FAILED";
export const RECIPIENT_FILTERS: readonly RecipientFilter[] = ["ALL", "UNREAD", "UNACKNOWLEDGED", "ACKNOWLEDGED", "FAILED"];

/** The filters worth showing: "not acknowledged" and "acknowledged" only for announcements that ask for it. */
export function recipientFilters(requiresAcknowledgement: boolean): RecipientFilter[] {
  return RECIPIENT_FILTERS.filter((f) => requiresAcknowledgement || (f !== "UNACKNOWLEDGED" && f !== "ACKNOWLEDGED"));
}

/** File name of the CSV download: no title (personal data in a file name is leaked by sync tools), only the date. */
export function csvFileName(announcementId: string, now: Date): string {
  const day = now.toISOString().slice(0, 10);
  return `announcement-${announcementId.slice(0, 8)}-${day}.csv`;
}

/** Percent of `part` in `whole`, 0 for nothing; for the report's meters. */
export function percent(part: number, whole: number): number {
  if (!(whole > 0) || !(part > 0)) return 0;
  return Math.min(100, Math.round((part / whole) * 100));
}

/** The reminder button: only for a published announcement that asks for an acknowledgement and when the API allows it. */
export function canRemind(a: { status: AnnouncementStatus; requiresAcknowledgement: boolean; can?: { remind?: boolean } }): boolean {
  return a.status === "PUBLISHED" && a.requiresAcknowledgement && !!a.can?.remind;
}

/** "2 h 5 min": how long until a reminder may be sent again, from seconds. */
export function waitParts(seconds: number): { hours: number; minutes: number } {
  const total = Math.max(0, Math.ceil(seconds / 60));
  return { hours: Math.floor(total / 60), minutes: total % 60 };
}

export type ChannelState = "sent" | "failed" | "waiting" | "held" | "suppressed" | "skipped" | "none";

/**
 * The delivery state of one channel as the report names it: push `SENT|HELD|PENDING|FAILED|SUPPRESSED|NONE`, email and
 * SMS `NOT_REQUESTED|PENDING|SENDING|SENT|FAILED|SKIPPED` (or `SKIPPED_<reason>`). Unknown values count as "none".
 */
export function channelState(state: string | null | undefined): ChannelState {
  if (!state) return "none";
  const s = state.toUpperCase();
  if (s === "SENT" || s === "DELIVERED") return "sent";
  if (s === "FAILED") return "failed";
  if (s === "PENDING" || s === "SENDING" || s === "QUEUED") return "waiting";
  if (s === "HELD") return "held";
  if (s === "SUPPRESSED") return "suppressed";
  if (s.startsWith("SKIPPED")) return "skipped";
  return "none";
}

// --- confirmation -------------------------------------------------------------------------

/** From this many recipients a publish asks "are you sure?" first (urgent and safety always do). */
export const LARGE_AUDIENCE = 25;

export function needsPublishConfirmation(recipients: number, type: AnnouncementType): boolean {
  return isUrgentType(type) || recipients >= LARGE_AUDIENCE;
}
