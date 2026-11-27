import { getBrowserApi } from "@/lib/api-client";
import { ensureOk, unwrap } from "@/lib/api-error";
import type { CreateGroupRequest } from "./types";
import type { SendRequest } from "./composer";

const api = () => getBrowserApi();

/** Page sizes: the inbox and the message list ("load older"). */
export const INBOX_PAGE = 25;
export const MESSAGE_PAGE = 30;

// --- reads --------------------------------------------------------------------------------------------------------

export const fetchInbox = async (schoolId: string | null, studentId: string | null, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/me/conversations", {
      params: { query: { schoolId: schoolId ?? undefined, studentId: studentId ?? undefined, cursor, limit: INBOX_PAGE } },
    }),
  );

export const fetchUnread = async () => unwrap(await api().GET("/api/v1/me/messaging/unread-count"));

export const fetchScheduled = async () => unwrap(await api().GET("/api/v1/me/scheduled-messages"));

export const fetchConversation = async (conversationId: string) =>
  unwrap(await api().GET("/api/v1/conversations/{conversationId}", { params: { path: { conversationId } } }));

export const fetchMembers = async (conversationId: string) =>
  unwrap(await api().GET("/api/v1/conversations/{conversationId}/members", { params: { path: { conversationId } } }));

export const fetchMessages = async (conversationId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/conversations/{conversationId}/messages", {
      params: { path: { conversationId }, query: { cursor, limit: MESSAGE_PAGE } },
    }),
  );

export const fetchCapabilities = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/messaging/capabilities", { params: { path: { schoolId } } }));

export const fetchContacts = async (schoolId: string, studentId: string, audience?: "PARENTS") =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/messaging/contacts", {
      params: { path: { schoolId }, query: { studentId, audience } },
    }),
  );

export const fetchGroups = async (schoolId: string, status?: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/messaging/groups", { params: { path: { schoolId }, query: { status } } }));

export const fetchBlocks = async () => unwrap(await api().GET("/api/v1/me/blocks"));

/** The caller's classes (as teacher or student) across schools: where a teacher starts a conversation from. */
export const fetchMyClasses = async () => unwrap(await api().GET("/api/v1/me/classes"));

// --- starting -----------------------------------------------------------------------------------------------------

export const startDirect = async (schoolId: string, recipientUserId: string, studentId: string | undefined, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/messaging/conversations/direct", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body: { recipientUserId, studentId },
    }),
  );

export const createGroup = async (schoolId: string, body: CreateGroupRequest, key: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/messaging/groups", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": key },
      body,
    }),
  );

// --- conversation -------------------------------------------------------------------------------------------------

export const markRead = async (conversationId: string, upToMessageId?: string) =>
  ensureOk(
    await api().POST("/api/v1/conversations/{conversationId}/read", {
      params: { path: { conversationId } },
      body: upToMessageId ? { upToMessageId } : undefined,
    }),
  );

export const muteConversation = async (conversationId: string, until: string) =>
  unwrap(await api().PUT("/api/v1/conversations/{conversationId}/mute", { params: { path: { conversationId } }, body: { until } }));

export const unmuteConversation = async (conversationId: string) =>
  ensureOk(await api().DELETE("/api/v1/conversations/{conversationId}/mute", { params: { path: { conversationId } } }));

export const leaveConversation = async (conversationId: string) =>
  ensureOk(await api().POST("/api/v1/conversations/{conversationId}/leave", { params: { path: { conversationId } } }));

export const updateConversation = async (conversationId: string, body: { title?: string; postingPolicy?: string; status?: string }) =>
  unwrap(await api().PATCH("/api/v1/conversations/{conversationId}", { params: { path: { conversationId } }, body }));

export const addMembers = async (conversationId: string, userIds: string[]) =>
  unwrap(await api().POST("/api/v1/conversations/{conversationId}/members", { params: { path: { conversationId } }, body: { userIds } }));

export const removeMember = async (conversationId: string, memberId: string, reason: string) =>
  unwrap(
    await api().POST("/api/v1/conversations/{conversationId}/members/{memberId}/remove", {
      params: { path: { conversationId, memberId } },
      body: { reason },
    }),
  );

export const approveGroup = async (conversationId: string) =>
  unwrap(await api().POST("/api/v1/conversations/{conversationId}/approve", { params: { path: { conversationId } } }));

export const rejectGroup = async (conversationId: string) =>
  unwrap(await api().POST("/api/v1/conversations/{conversationId}/reject", { params: { path: { conversationId } } }));

// --- messages -----------------------------------------------------------------------------------------------------

/** The API answers 201 for a new message and 200 for a repeat of the same `clientMessageId`; both carry the stored message. */
export const sendMessage = async (conversationId: string, request: SendRequest) =>
  unwrap(await api().POST("/api/v1/conversations/{conversationId}/messages", { params: { path: { conversationId } }, body: request }));

export const editMessage = async (conversationId: string, messageId: string, body: string) =>
  unwrap(
    await api().PATCH("/api/v1/conversations/{conversationId}/messages/{messageId}", {
      params: { path: { conversationId, messageId } },
      body: { body },
    }),
  );

export const deleteMessage = async (conversationId: string, messageId: string) =>
  ensureOk(await api().DELETE("/api/v1/conversations/{conversationId}/messages/{messageId}", { params: { path: { conversationId, messageId } } }));

export const reactToMessage = async (conversationId: string, messageId: string, emoji: string) =>
  unwrap(
    await api().PUT("/api/v1/conversations/{conversationId}/messages/{messageId}/reaction", {
      params: { path: { conversationId, messageId } },
      body: { emoji },
    }),
  );

export const unreactToMessage = async (conversationId: string, messageId: string, emoji: string) =>
  unwrap(
    await api().DELETE("/api/v1/conversations/{conversationId}/messages/{messageId}/reaction", {
      params: { path: { conversationId, messageId }, query: { emoji } },
    }),
  );

export const hideMessage = async (conversationId: string, messageId: string, reason: string) =>
  ensureOk(
    await api().POST("/api/v1/conversations/{conversationId}/messages/{messageId}/hide", {
      params: { path: { conversationId, messageId } },
      body: { reason },
    }),
  );

export const unhideMessage = async (conversationId: string, messageId: string) =>
  ensureOk(
    await api().POST("/api/v1/conversations/{conversationId}/messages/{messageId}/unhide", { params: { path: { conversationId, messageId } } }),
  );

export const translateMessage = async (conversationId: string, messageId: string, language: string) =>
  unwrap(
    await api().POST("/api/v1/conversations/{conversationId}/messages/{messageId}/translation", {
      params: { path: { conversationId, messageId }, query: { language } },
    }),
  );

// --- blocks -------------------------------------------------------------------------------------------------------

export const blockPerson = async (conversationId: string, memberId: string) =>
  unwrap(await api().POST("/api/v1/me/blocks", { body: { conversationId, memberId } }));

export const unblockPerson = async (blockId: string) => ensureOk(await api().DELETE("/api/v1/me/blocks/{blockId}", { params: { path: { blockId } } }));

// --- review (reviewers only) --------------------------------------------------------------------------------------

export const fetchReviewConversations = async (schoolId: string, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/messaging/review/conversations", {
      params: { path: { schoolId }, query: { cursor, limit: 25 } },
    }),
  );

export const fetchReports = async (schoolId: string, status?: "OPEN" | "RESOLVED") =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/messaging/review/reports", { params: { path: { schoolId }, query: { status } } }));

export const fetchStudentBlocks = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/messaging/review/blocks", { params: { path: { schoolId } } }));

/** Reads a reviewable conversation. A POST because every call is audited with the reason: never cached, never a link. */
export const reviewConversation = async (schoolId: string, conversationId: string, reason: string, cursor?: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/messaging/review/conversations/{conversationId}/read", {
      params: { path: { schoolId, conversationId } },
      body: { reason, cursor, limit: 30 },
    }),
  );
