import { isProblemDetail, type components } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ApiRequestError, ensureOk, retryAfterOf, unwrap } from "@/lib/api-error";
import type { InboxFilter, InboxSort, RecipientFilter } from "./view";

type S = components["schemas"];

export type Announcement = S["AnnouncementView"];
export type AnnouncementCard = S["AnnouncementCard"];
export type AnnouncementStatus = Announcement["status"];
export type AnnouncementType = Announcement["type"];
export type Capabilities = S["AnnouncementCapabilities"];
export type CreateRequest = S["AnnouncementRequest"];
export type UpdateRequest = S["AnnouncementUpdateRequest"];
export type AudienceInput = S["AnnouncementAudienceInput"];
export type Revision = S["AnnouncementRevisionView"];
export type Preview = S["AnnouncementPreview"];
export type ReportSummary = S["AnnouncementReportSummary"];
export type RecipientRow = S["AnnouncementRecipientView"];
export type UnreadSummary = S["AnnouncementUnreadSummary"];
export type ChannelPreferences = S["CommunicationChannelsView"];
export type ChannelCounts = S["ChannelCounts"];
export type Translations = NonNullable<CreateRequest["translations"]>;

/** Page size of the announcement lists ("Load more"). */
export const PAGE = 20;
/** Recipient rows per page of the report. */
export const RECIPIENT_PAGE = 50;

const api = () => getBrowserApi();

/**
 * Query keys. Titles and bodies are personal data: they live in this memory-only cache, never in a URL or storage.
 * `schoolId` comes first wherever the data belongs to a school, so one school's data can be dropped at once.
 */
export const ank = {
  all: ["announcements"] as const,
  summary: ["announcements", "me", "summary"] as const,
  inbox: (schoolId: string | null, filter: InboxFilter, sort: InboxSort) => ["announcements", "me", "inbox", schoolId ?? "all", filter, sort] as const,
  /** The few newest announcements of the person, for the pinned card on the feed screens. */
  latest: (schoolId: string | null) => ["announcements", "me", "latest", schoolId ?? "all"] as const,
  school: (schoolId: string) => ["announcements", schoolId] as const,
  schoolList: (schoolId: string, filter: InboxFilter, sort: InboxSort) => ["announcements", schoolId, "list", filter, sort] as const,
  authored: (schoolId: string, status: string, mine: boolean) => ["announcements", schoolId, "authored", status, mine] as const,
  capabilities: (schoolId: string) => ["announcements", schoolId, "capabilities"] as const,
  one: (schoolId: string, id: string) => ["announcements", schoolId, "one", id] as const,
  revisions: (schoolId: string, id: string) => ["announcements", schoolId, "one", id, "revisions"] as const,
  preview: (schoolId: string, id: string) => ["announcements", schoolId, "one", id, "preview"] as const,
  report: (schoolId: string, id: string) => ["announcements", schoolId, "one", id, "report"] as const,
  recipients: (schoolId: string, id: string, filter: RecipientFilter) => ["announcements", schoolId, "one", id, "recipients", filter] as const,
  channels: ["announcements", "me", "channels"] as const,
};

// --- reads ---------------------------------------------------------------------------------

export const fetchSummary = async () => unwrap(await api().GET("/api/v1/me/announcements/summary"));

export const fetchInbox = async (schoolId: string | null, filter: InboxFilter, sort: InboxSort, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/me/announcements", {
      params: { query: { schoolId: schoolId ?? undefined, filter: filter === "ALL" ? undefined : filter, sort: sort === "NEWEST" ? undefined : sort, cursor, limit: PAGE } },
    }),
  );

/** How many cards the feed's pinned announcement looks at (the newest that is published is shown). */
export const LATEST_LOOKAHEAD = 3;

export const fetchLatest = async (schoolId: string | null) =>
  unwrap(await api().GET("/api/v1/me/announcements", { params: { query: { schoolId: schoolId ?? undefined, limit: LATEST_LOOKAHEAD } } }));

export const fetchSchoolList = async (schoolId: string, filter: InboxFilter, sort: InboxSort, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/announcements", {
      params: {
        path: { schoolId },
        query: { filter: filter === "ALL" ? undefined : filter, sort: sort === "NEWEST" ? undefined : sort, cursor, limit: PAGE },
      },
    }),
  );

export const fetchAuthored = async (schoolId: string, status: AnnouncementStatus | undefined, mine: boolean, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/announcements/authored", {
      params: { path: { schoolId }, query: { status, mine: mine || undefined, cursor, limit: PAGE } },
    }),
  );

export const fetchCapabilities = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/announcements/capabilities", { params: { path: { schoolId } } }));

export const fetchAnnouncement = async (schoolId: string, announcementId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/announcements/{announcementId}", { params: { path: { schoolId, announcementId } } }));

export const fetchRevisions = async (schoolId: string, announcementId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/announcements/{announcementId}/revisions", { params: { path: { schoolId, announcementId } } }));

export const fetchPreview = async (schoolId: string, announcementId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/announcements/{announcementId}/preview", { params: { path: { schoolId, announcementId } } }));

export const fetchReport = async (schoolId: string, announcementId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/announcements/{announcementId}/report", { params: { path: { schoolId, announcementId } } }));

export const fetchRecipients = async (schoolId: string, announcementId: string, filter: RecipientFilter, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/announcements/{announcementId}/report/recipients", {
      params: {
        path: { schoolId, announcementId },
        query: { filter: filter === "ALL" ? undefined : filter, cursor, limit: RECIPIENT_PAGE },
      },
    }),
  );

export const fetchChannels = async () => unwrap(await api().GET("/api/v1/me/communication-channels"));

// --- writes --------------------------------------------------------------------------------

export const createAnnouncement = async (schoolId: string, body: CreateRequest, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/announcements", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const updateAnnouncement = async (schoolId: string, announcementId: string, body: UpdateRequest) =>
  unwrap(await api().PATCH("/api/v1/schools/{schoolId}/announcements/{announcementId}", { params: { path: { schoolId, announcementId } }, body }));

export const deleteAnnouncement = async (schoolId: string, announcementId: string) =>
  ensureOk(await api().DELETE("/api/v1/schools/{schoolId}/announcements/{announcementId}", { params: { path: { schoolId, announcementId } } }));

export const publishAnnouncement = async (schoolId: string, announcementId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/publish", { params: { path: { schoolId, announcementId } } }));

export const scheduleAnnouncement = async (schoolId: string, announcementId: string, scheduledFor: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/schedule", {
      params: { path: { schoolId, announcementId } },
      body: { scheduledFor },
    }),
  );

export const unscheduleAnnouncement = async (schoolId: string, announcementId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/unschedule", { params: { path: { schoolId, announcementId } } }));

export const withdrawAnnouncement = async (schoolId: string, announcementId: string, reason: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/withdraw", {
      params: { path: { schoolId, announcementId } },
      body: { reason: reason.trim() || undefined },
    }),
  );

export const markAnnouncementRead = async (schoolId: string, announcementId: string) =>
  ensureOk(await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/read", { params: { path: { schoolId, announcementId } } }));

export const acknowledgeAnnouncement = async (schoolId: string, announcementId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/acknowledge", { params: { path: { schoolId, announcementId } } }));

export const remindUnacknowledged = async (schoolId: string, announcementId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/announcements/{announcementId}/remind", { params: { path: { schoolId, announcementId } } }));

export const updateChannels = async (body: { email?: boolean; sms?: boolean }) =>
  unwrap(await api().PUT("/api/v1/me/communication-channels", { body }));

/**
 * Downloads the recipient CSV through the BFF (never a link with a token). `no-store`: the file is personal data and
 * every export is audited, so no cache may keep or replay it. Throws `ApiRequestError` with the API's problem on
 * failure (403 no access, 429 `announcement.export_rate_limited`, 422 `announcement.export_too_large`).
 */
export async function downloadReportCsv(schoolId: string, announcementId: string): Promise<Blob> {
  const response = await fetch(
    `${window.location.origin}/api/bff/api/v1/schools/${encodeURIComponent(schoolId)}/announcements/${encodeURIComponent(announcementId)}/report.csv`,
    {
      cache: "no-store",
      headers: { Accept: "text/csv, application/problem+json", "Accept-Language": document.documentElement.lang || "en" },
    },
  );
  if (!response.ok) {
    let problem: unknown;
    try {
      problem = await response.json();
    } catch {
      problem = undefined;
    }
    throw new ApiRequestError(response.status, isProblemDetail(problem) ? problem : undefined, retryAfterOf(response));
  }
  return response.blob();
}
