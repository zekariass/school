import type { components } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ensureOk, unwrap } from "@/lib/api-error";

type S = components["schemas"];

export type PortfolioItem = S["PortfolioItemView"];
export type PortfolioStatus = PortfolioItem["status"];
export type PortfolioVisibility = PortfolioItem["visibility"];
export type PortfolioItemType = PortfolioItem["itemType"];
export type PortfolioMedia = S["PortfolioMediaView"];
export type PortfolioItemCapabilities = S["PortfolioItemCapabilities"];
export type PortfolioCapabilities = S["PortfolioCapabilities"];
export type PortfolioHistoryEntry = S["PortfolioHistoryEntry"];
export type PortfolioComment = S["PortfolioCommentView"];
export type PortfolioItemCreate = S["PortfolioItemRequest"];
export type PortfolioItemUpdate = S["PortfolioItemUpdateRequest"];
export type PortfolioQuickCapture = S["PortfolioQuickCaptureRequest"];
export type PortfolioReview = S["PortfolioReviewRequest"];
export type PortfolioDecision = PortfolioReview["decision"];

/** Page size of the lists ("Load more"). */
export const PORTFOLIO_PAGE = 12;
const COMMENT_PAGE = 20;

/**
 * Portfolio data is a child's own work: it lives in TanStack memory only, and only briefly after the screen that
 * used it is gone (three minutes), never in storage, a URL, a title or a log.
 */
export const PORTFOLIO_GC_MS = 3 * 60_000;

const api = () => getBrowserApi();

/** Query keys. They hold titles, notes and comments in memory only and never appear in a URL. */
export const pfk = {
  all: ["portfolio"] as const,
  capabilities: (schoolId: string) => ["portfolio", schoolId, "capabilities"] as const,
  mine: (schoolId: string, status: string) => ["portfolio", schoolId, "mine", status] as const,
  queue: (schoolId: string, classId: string) => ["portfolio", schoolId, "queue", classId] as const,
  classGallery: (schoolId: string, classId: string) => ["portfolio", schoolId, "gallery", "class", classId] as const,
  schoolGallery: (schoolId: string) => ["portfolio", schoolId, "gallery", "school"] as const,
  family: (studentId: string | null) => ["portfolio", "family", studentId ?? "all"] as const,
  item: (schoolId: string, itemId: string) => ["portfolio", schoolId, "item", itemId] as const,
  history: (schoolId: string, itemId: string) => ["portfolio", schoolId, "item", itemId, "history"] as const,
  comments: (schoolId: string, itemId: string) => ["portfolio", schoolId, "item", itemId, "comments"] as const,
  myClasses: ["portfolio", "my-classes"] as const,
};

export const fetchCapabilities = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/portfolio/capabilities", { params: { path: { schoolId } } }));

export const fetchMyClasses = async () => unwrap(await api().GET("/api/v1/me/classes", { params: { query: {} } }));

export const fetchMine = async (schoolId: string, status?: PortfolioStatus, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/portfolio/mine", {
      params: { path: { schoolId }, query: { status, cursor, limit: PORTFOLIO_PAGE } },
    }),
  );

export const fetchQueue = async (schoolId: string, classId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/portfolio/review-queue", {
      params: { path: { schoolId, classId }, query: { cursor, limit: PORTFOLIO_PAGE } },
    }),
  );

export const fetchClassGallery = async (schoolId: string, classId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/portfolio/gallery", {
      params: { path: { schoolId, classId }, query: { cursor, limit: PORTFOLIO_PAGE } },
    }),
  );

export const fetchSchoolGallery = async (schoolId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/portfolio/gallery", {
      params: { path: { schoolId }, query: { cursor, limit: PORTFOLIO_PAGE } },
    }),
  );

export const fetchFamilyPortfolio = async (studentId: string | null, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/me/portfolio", {
      params: { query: { studentId: studentId ?? undefined, cursor, limit: PORTFOLIO_PAGE } },
    }),
  );

export const fetchItem = async (schoolId: string, itemId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/portfolio/items/{itemId}", { params: { path: { schoolId, itemId } } }));

export const fetchHistory = async (schoolId: string, itemId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/history", { params: { path: { schoolId, itemId } } }));

export const fetchComments = async (schoolId: string, itemId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments", {
      params: { path: { schoolId, itemId }, query: { cursor, limit: COMMENT_PAGE } },
    }),
  );

// --- writes ------------------------------------------------------------------------------------

/** Creates carry an `Idempotency-Key` (the key stays the same while the same submission is retried). */
export const createItem = async (schoolId: string, body: PortfolioItemCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/portfolio/items", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const quickCapture = async (schoolId: string, classId: string, body: PortfolioQuickCapture, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/classes/{classId}/portfolio/quick-capture", {
      params: { path: { schoolId, classId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const updateItem = async (schoolId: string, itemId: string, body: PortfolioItemUpdate) =>
  unwrap(await api().PATCH("/api/v1/schools/{schoolId}/portfolio/items/{itemId}", { params: { path: { schoolId, itemId } }, body }));

export const submitItem = async (schoolId: string, itemId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/submit", { params: { path: { schoolId, itemId } } }));

export const deleteItem = async (schoolId: string, itemId: string) =>
  ensureOk(await api().DELETE("/api/v1/schools/{schoolId}/portfolio/items/{itemId}", { params: { path: { schoolId, itemId } } }));

export const reviewItem = async (schoolId: string, itemId: string, body: PortfolioReview) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/review", { params: { path: { schoolId, itemId } }, body }));

export const archiveItem = async (schoolId: string, itemId: string, note?: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/archive", {
      params: { path: { schoolId, itemId } },
      body: { note },
    }),
  );

export const unarchiveItem = async (schoolId: string, itemId: string, note?: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/unarchive", {
      params: { path: { schoolId, itemId } },
      body: { note },
    }),
  );

export const setFeatured = async (schoolId: string, itemId: string, featured: boolean) =>
  unwrap(
    featured
      ? await api().PUT("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/featured", { params: { path: { schoolId, itemId } } })
      : await api().DELETE("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/featured", { params: { path: { schoolId, itemId } } }),
  );

export const createComment = async (schoolId: string, itemId: string, body: string, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments", {
      params: { path: { schoolId, itemId } },
      headers: { "Idempotency-Key": key },
      body: { body },
    }),
  );

export const hideComment = async (schoolId: string, itemId: string, commentId: string, reason: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments/{commentId}/hide", {
      params: { path: { schoolId, itemId, commentId } },
      body: { reason },
    }),
  );

export const deleteComment = async (schoolId: string, itemId: string, commentId: string) =>
  ensureOk(
    await api().DELETE("/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments/{commentId}", {
      params: { path: { schoolId, itemId, commentId } },
    }),
  );
