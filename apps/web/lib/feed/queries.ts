import type { components } from "@school/api-client";
import { getBrowserApi } from "@/lib/api-client";
import { ensureOk, unwrap } from "@/lib/api-error";
import type { ReactionName } from "./reactions";

type S = components["schemas"];

export type Post = S["PostView"];
export type PostStatus = Post["status"];
export type Story = S["StoryView"];
export type FeedComment = S["CommentView"];
export type FeedCapabilities = S["FeedCapabilities"];
export type FeedMediaItem = S["FeedMediaItem"];
export type FeedStudentTag = S["FeedStudentTag"];
export type FeedAuthor = S["FeedAuthor"];
export type PostCreate = S["PostCreateRequest"];
export type PostUpdate = S["PostUpdateRequest"];
export type StoryCreate = S["StoryCreateRequest"];
export type ContributionCreate = S["ContributionCreateRequest"];
export type Translation = S["TranslationView"];

/** Page size of the feed lists ("Load more"). */
export const FEED_PAGE = 10;
const COMMENT_PAGE = 20;

const api = () => getBrowserApi();

/**
 * Query keys of the feed. They hold children's photos' ids and text in memory only (never persisted), and never
 * appear in a URL. `schoolId` first so everything of a school can be dropped at once.
 */
export const fk = {
  all: ["feed"] as const,
  school: (schoolId: string) => ["feed", schoolId] as const,
  capabilities: (schoolId: string, classId?: string) => ["feed", schoolId, "capabilities", classId ?? "school"] as const,
  classFeed: (schoolId: string, classId: string) => ["feed", schoolId, "class", classId, "posts"] as const,
  schoolFeed: (schoolId: string) => ["feed", schoolId, "school", "posts"] as const,
  classStories: (schoolId: string, classId: string) => ["feed", schoolId, "class", classId, "stories"] as const,
  schoolStories: (schoolId: string) => ["feed", schoolId, "school", "stories"] as const,
  myFeed: (studentId: string | null) => ["feed", "me", "posts", studentId ?? "all"] as const,
  myStories: (studentId: string | null) => ["feed", "me", "stories", studentId ?? "all"] as const,
  myPosts: (schoolId: string, status: string, classId?: string) => ["feed", schoolId, "mine", status, classId ?? "all"] as const,
  pending: (schoolId: string, classId?: string) => ["feed", schoolId, "pending", classId ?? "all"] as const,
  post: (schoolId: string, postId: string) => ["feed", schoolId, "post", postId] as const,
  story: (schoolId: string, storyId: string) => ["feed", schoolId, "story", storyId] as const,
  comments: (schoolId: string, postId: string) => ["feed", schoolId, "post", postId, "comments"] as const,
};

export const fetchCapabilities = async (schoolId: string, classId?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/feed/capabilities", {
      params: { path: { schoolId }, query: { classId } },
    }),
  );

export const fetchClassFeed = async (schoolId: string, classId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/feed", {
      params: { path: { schoolId, classId }, query: { cursor, limit: FEED_PAGE } },
    }),
  );

export const fetchSchoolFeed = async (schoolId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/feed", { params: { path: { schoolId }, query: { cursor, limit: FEED_PAGE } } }),
  );

export const fetchMyFeed = async (studentId: string | null, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/me/feed", { params: { query: { studentId: studentId ?? undefined, cursor, limit: FEED_PAGE } } }),
  );

export const fetchMyPosts = async (schoolId: string, status?: PostStatus, classId?: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/my-posts", {
      params: { path: { schoolId }, query: { status, classId, cursor, limit: FEED_PAGE } },
    }),
  );

export const fetchPost = async (schoolId: string, postId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/posts/{postId}", { params: { path: { schoolId, postId } } }));

export const fetchPending = async (schoolId: string, classId?: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/contributions/pending", {
      params: { path: { schoolId }, query: { classId, cursor, limit: FEED_PAGE } },
    }),
  );

export const fetchClassStories = async (schoolId: string, classId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/classes/{classId}/stories", { params: { path: { schoolId, classId } } }));

export const fetchSchoolStories = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/stories", { params: { path: { schoolId } } }));

export const fetchMyStories = async (studentId: string | null) =>
  unwrap(await api().GET("/api/v1/me/stories", { params: { query: { studentId: studentId ?? undefined } } }));

export const fetchStory = async (schoolId: string, storyId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/stories/{storyId}", { params: { path: { schoolId, storyId } } }));

export const fetchComments = async (schoolId: string, postId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/posts/{postId}/comments", {
      params: { path: { schoolId, postId }, query: { cursor, limit: COMMENT_PAGE } },
    }),
  );

// --- writes ------------------------------------------------------------------------------

export const createClassPost = async (schoolId: string, classId: string, body: PostCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/classes/{classId}/posts", {
      params: { path: { schoolId, classId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const createSchoolPost = async (schoolId: string, body: PostCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const updatePost = async (schoolId: string, postId: string, body: PostUpdate) =>
  unwrap(await api().PATCH("/api/v1/schools/{schoolId}/posts/{postId}", { params: { path: { schoolId, postId } }, body }));

export const deletePost = async (schoolId: string, postId: string) =>
  ensureOk(await api().DELETE("/api/v1/schools/{schoolId}/posts/{postId}", { params: { path: { schoolId, postId } } }));

export const publishPost = async (schoolId: string, postId: string, confirmNoUnconsentedStudents?: boolean) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/publish", {
      params: { path: { schoolId, postId } },
      body: { confirmNoUnconsentedStudents },
    }),
  );

export const schedulePost = async (schoolId: string, postId: string, scheduledFor: string, confirmNoUnconsentedStudents?: boolean) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/schedule", {
      params: { path: { schoolId, postId } },
      body: { scheduledFor, confirmNoUnconsentedStudents },
    }),
  );

export const unschedulePost = async (schoolId: string, postId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/unschedule", { params: { path: { schoolId, postId } } }));

export const archivePost = async (schoolId: string, postId: string) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/archive", { params: { path: { schoolId, postId } } }));

export const hidePost = async (schoolId: string, postId: string, reason?: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/hide", {
      params: { path: { schoolId, postId } },
      body: { reason: reason?.trim() || undefined },
    }),
  );

export const unhidePost = async (schoolId: string, postId: string, confirmNoUnconsentedStudents?: boolean) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/unhide", {
      params: { path: { schoolId, postId } },
      body: { confirmNoUnconsentedStudents },
    }),
  );

export const setPinned = async (schoolId: string, postId: string, pinned: boolean) =>
  unwrap(
    pinned
      ? await api().PUT("/api/v1/schools/{schoolId}/posts/{postId}/pin", { params: { path: { schoolId, postId } } })
      : await api().DELETE("/api/v1/schools/{schoolId}/posts/{postId}/pin", { params: { path: { schoolId, postId } } }),
  );

export const react = async (schoolId: string, postId: string, reaction: ReactionName | null) =>
  unwrap(
    reaction
      ? await api().PUT("/api/v1/schools/{schoolId}/posts/{postId}/reaction", {
          params: { path: { schoolId, postId } },
          body: { reaction },
        })
      : await api().DELETE("/api/v1/schools/{schoolId}/posts/{postId}/reaction", { params: { path: { schoolId, postId } } }),
  );

export const markPostSeen = async (schoolId: string, postId: string) =>
  ensureOk(await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/view", { params: { path: { schoolId, postId } } }));

export const translatePost = async (schoolId: string, postId: string, language: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/translation", {
      params: { path: { schoolId, postId }, query: { language } },
    }),
  );

export const translateComment = async (schoolId: string, postId: string, commentId: string, language: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/translation", {
      params: { path: { schoolId, postId, commentId }, query: { language } },
    }),
  );

export const addComment = async (schoolId: string, postId: string, body: string, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/comments", {
      params: { path: { schoolId, postId } },
      headers: { "Idempotency-Key": key },
      body: { body },
    }),
  );

export const deleteComment = async (schoolId: string, postId: string, commentId: string) =>
  ensureOk(
    await api().DELETE("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}", {
      params: { path: { schoolId, postId, commentId } },
    }),
  );

export const setCommentHidden = async (schoolId: string, postId: string, commentId: string, hidden: boolean) =>
  unwrap(
    hidden
      ? await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/hide", {
          params: { path: { schoolId, postId, commentId } },
        })
      : await api().POST("/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}/unhide", {
          params: { path: { schoolId, postId, commentId } },
        }),
  );

export const createClassStory = async (schoolId: string, classId: string, body: StoryCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/classes/{classId}/stories", {
      params: { path: { schoolId, classId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const createSchoolStory = async (schoolId: string, body: StoryCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/stories", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const markStoryViewed = async (schoolId: string, storyId: string) =>
  ensureOk(await api().POST("/api/v1/schools/{schoolId}/stories/{storyId}/view", { params: { path: { schoolId, storyId } } }));

export const deleteStory = async (schoolId: string, storyId: string) =>
  ensureOk(await api().DELETE("/api/v1/schools/{schoolId}/stories/{storyId}", { params: { path: { schoolId, storyId } } }));

export const createContribution = async (schoolId: string, classId: string, body: ContributionCreate, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/classes/{classId}/contributions", {
      params: { path: { schoolId, classId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

export const approveContribution = async (schoolId: string, postId: string, body: S["ContributionApproveRequest"]) =>
  unwrap(await api().POST("/api/v1/schools/{schoolId}/contributions/{postId}/approve", { params: { path: { schoolId, postId } }, body }));

export const rejectContribution = async (schoolId: string, postId: string, reason: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/contributions/{postId}/reject", {
      params: { path: { schoolId, postId } },
      body: { reason },
    }),
  );
