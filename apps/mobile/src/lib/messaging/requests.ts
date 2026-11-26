import type { components } from '@school/api-client';

import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';

import { INBOX_PAGE_SIZE } from './keys';
import { classifyFailure, type OutboxEntry, type SendOutcome } from './outbox';
import { MESSAGE_PAGE_SIZE } from './types';
import type {
  BlockedPerson,
  Contact,
  ConversationDetail,
  ConversationPage,
  GroupAdmin,
  GroupKind,
  MemberList,
  MessageItem,
  MessagePage,
  MessagingCapabilities,
  ReportReason,
  SendResult,
} from './types';

type S = components['schemas'];

/**
 * One call per endpoint, typed from the generated client. Message text goes through these functions in memory only; nothing is
 * logged or stored. Only the two creates the API marks `@Idempotent` (starting a DIRECT conversation, creating a group) carry an
 * `Idempotency-Key`; a message is made idempotent by its `clientMessageId`.
 */

const conversationPath = (conversationId: string) => ({ params: { path: { conversationId } } });
const messagePath = (conversationId: string, messageId: string) => ({ params: { path: { conversationId, messageId } } });

// --- reading ---------------------------------------------------------------------------------------------------------

export const fetchInbox = (schoolId: string | null, studentId: string | null, cursor?: string): Promise<ConversationPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/me/conversations', {
        params: { query: { limit: INBOX_PAGE_SIZE, ...(schoolId ? { schoolId } : {}), ...(studentId ? { studentId } : {}), ...(cursor ? { cursor } : {}) } },
      }),
    ),
  );

export const fetchUnreadCount = (): Promise<number> =>
  request(async () => unwrap(await api.GET('/api/v1/me/messaging/unread-count')).unread);

export const fetchConversation = (conversationId: string): Promise<ConversationDetail> =>
  request(async () => unwrap(await api.GET('/api/v1/conversations/{conversationId}', conversationPath(conversationId))));

export const fetchMembers = (conversationId: string): Promise<MemberList> =>
  request(async () => unwrap(await api.GET('/api/v1/conversations/{conversationId}/members', conversationPath(conversationId))));

export const fetchMessages = (conversationId: string, cursor?: string): Promise<MessagePage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/conversations/{conversationId}/messages', {
        params: { path: { conversationId }, query: { limit: MESSAGE_PAGE_SIZE, ...(cursor ? { cursor } : {}) } },
      }),
    ),
  );

export const fetchMessage = (conversationId: string, messageId: string): Promise<MessageItem> =>
  request(async () => unwrap(await api.GET('/api/v1/conversations/{conversationId}/messages/{messageId}', messagePath(conversationId, messageId))));

export const fetchScheduled = (): Promise<MessageItem[]> => request(async () => unwrap(await api.GET('/api/v1/me/scheduled-messages')));

export const fetchCapabilities = (schoolId: string): Promise<MessagingCapabilities> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/messaging/capabilities', { params: { path: { schoolId } } })));

export const fetchContacts = (schoolId: string, studentId: string, audience?: 'PARENTS'): Promise<Contact[]> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/messaging/contacts', {
        params: { path: { schoolId }, query: { studentId, ...(audience ? { audience } : {}) } },
      }),
    ),
  );

export const fetchGroups = (schoolId: string, status: string | null): Promise<GroupAdmin[]> =>
  request(async () =>
    unwrap(await api.GET('/api/v1/schools/{schoolId}/messaging/groups', { params: { path: { schoolId }, query: status ? { status } : {} } })),
  );

export const fetchBlocks = (): Promise<BlockedPerson[]> => request(async () => unwrap(await api.GET('/api/v1/me/blocks')));

// --- sending ---------------------------------------------------------------------------------------------------------

export interface SendInput {
  clientMessageId: string;
  body: string;
  mediaIds: readonly string[];
  replyToMessageId?: string | undefined;
  /** An ISO instant (a scheduled message). */
  sendAt?: string | undefined;
  bodyLanguageCode?: string | undefined;
}

function sendBody(input: SendInput): S['SendMessageRequest'] {
  return {
    clientMessageId: input.clientMessageId,
    ...(input.body.length > 0 ? { body: input.body } : {}),
    messageType: input.mediaIds.length > 0 ? 'MEDIA' : 'TEXT',
    ...(input.mediaIds.length > 0 ? { mediaIds: [...input.mediaIds] } : {}),
    ...(input.replyToMessageId ? { replyToMessageId: input.replyToMessageId } : {}),
    ...(input.sendAt ? { sendAt: input.sendAt } : {}),
    ...(input.bodyLanguageCode ? { bodyLanguageCode: input.bodyLanguageCode } : {}),
  };
}

/**
 * Sends a message and answers the way the outbox reads it: accepted (201, or 200 for a message that was stored already: the same
 * `clientMessageId` never makes a second message), retryable (no answer, 401, 408, 425, 429, 5xx) or final (the other 4xx, with the
 * API's localized problem). A transport failure never throws.
 */
export async function sendMessage(conversationId: string, input: SendInput): Promise<SendOutcome> {
  try {
    const result = await api.POST('/api/v1/conversations/{conversationId}/messages', {
      params: { path: { conversationId } },
      body: sendBody(input),
    });
    if (result.data !== undefined && result.response.ok) return { kind: 'sent', result: result.data as SendResult };
    const problem = (result.error ?? undefined) as { code?: string; title?: string; detail?: string } | undefined;
    const retryAfter = Number(result.response.headers?.get('Retry-After'));
    return classifyFailure({
      status: result.response.status,
      retryAfterSeconds: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
      problem: problem && typeof problem === 'object' ? { code: problem.code, title: problem.title, detail: problem.detail } : undefined,
    });
  } catch {
    return classifyFailure({});
  }
}

/** The outbox's transport: the same call for every attempt of an entry, with the entry's own `clientMessageId`. */
export function outboxTransport(entry: OutboxEntry): Promise<SendOutcome> {
  return sendMessage(entry.conversationId, {
    clientMessageId: entry.clientMessageId,
    body: entry.body,
    mediaIds: entry.mediaIds,
    replyToMessageId: entry.replyToMessageId,
  });
}

/** A scheduled message goes straight to the API (a pending schedule is not an unsent message): the caller shows any problem. */
export const scheduleMessage = (conversationId: string, input: SendInput & { sendAt: string }): Promise<SendResult> =>
  request(async () => unwrap(await api.POST('/api/v1/conversations/{conversationId}/messages', { params: { path: { conversationId } }, body: sendBody(input) })));

export const editMessage = (conversationId: string, messageId: string, body: string): Promise<MessageItem> =>
  request(async () => unwrap(await api.PATCH('/api/v1/conversations/{conversationId}/messages/{messageId}', { ...messagePath(conversationId, messageId), body: { body } })));

export const deleteMessage = (conversationId: string, messageId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/conversations/{conversationId}/messages/{messageId}', messagePath(conversationId, messageId))));

export const putReaction = (conversationId: string, messageId: string, emoji: string): Promise<MessageItem> =>
  request(async () => unwrap(await api.PUT('/api/v1/conversations/{conversationId}/messages/{messageId}/reaction', { ...messagePath(conversationId, messageId), body: { emoji } })));

export const deleteReaction = (conversationId: string, messageId: string, emoji: string): Promise<MessageItem> =>
  request(async () =>
    unwrap(
      await api.DELETE('/api/v1/conversations/{conversationId}/messages/{messageId}/reaction', {
        params: { path: { conversationId, messageId }, query: { emoji } },
      }),
    ),
  );

export const reportMessage = (conversationId: string, messageId: string, reason: ReportReason, note?: string): Promise<void> =>
  request(async () =>
    assertOk(
      await api.POST('/api/v1/conversations/{conversationId}/messages/{messageId}/report', {
        ...messagePath(conversationId, messageId),
        body: { reason, ...(note ? { note } : {}) },
      }),
    ),
  );

export const hideMessage = (conversationId: string, messageId: string, reason: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/conversations/{conversationId}/messages/{messageId}/hide', { ...messagePath(conversationId, messageId), body: { reason } })));

export const unhideMessage = (conversationId: string, messageId: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/conversations/{conversationId}/messages/{messageId}/unhide', messagePath(conversationId, messageId))));

// --- the conversation itself ------------------------------------------------------------------------------------------

export const markRead = (conversationId: string, upToMessageId?: string): Promise<void> =>
  request(async () =>
    assertOk(await api.POST('/api/v1/conversations/{conversationId}/read', { ...conversationPath(conversationId), body: upToMessageId ? { upToMessageId } : {} })),
  );

export const muteConversation = (conversationId: string, until: string): Promise<void> =>
  request(async () => assertOk(await api.PUT('/api/v1/conversations/{conversationId}/mute', { ...conversationPath(conversationId), body: { until } })));

export const unmuteConversation = (conversationId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/conversations/{conversationId}/mute', conversationPath(conversationId))));

export const leaveConversation = (conversationId: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/conversations/{conversationId}/leave', conversationPath(conversationId))));

export const blockMember = (conversationId: string, memberId: string): Promise<BlockedPerson> =>
  request(async () => unwrap(await api.POST('/api/v1/me/blocks', { body: { conversationId, memberId } })));

export const unblock = (blockId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/me/blocks/{blockId}', { params: { path: { blockId } } })));

// --- starting and managing -------------------------------------------------------------------------------------------

export interface StartedConversation {
  conversation: ConversationDetail;
  created: boolean;
}

export const startDirect = (schoolId: string, recipientUserId: string, studentId: string | null, idempotencyKey: string): Promise<StartedConversation> =>
  request(async () => {
    const result = await api.POST('/api/v1/schools/{schoolId}/messaging/conversations/direct', {
      params: { path: { schoolId } },
      body: { recipientUserId, ...(studentId ? { studentId } : {}) },
      headers: { 'Idempotency-Key': idempotencyKey },
    });
    const conversation = unwrap(result);
    return { conversation, created: result.response.status === 201 };
  });

export interface NewGroup {
  kind: GroupKind;
  classId?: string | undefined;
  title?: string | undefined;
  memberUserIds?: readonly string[] | undefined;
}

export const createGroup = (schoolId: string, group: NewGroup, idempotencyKey: string): Promise<GroupAdmin> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/messaging/groups', {
        params: { path: { schoolId } },
        body: {
          kind: group.kind,
          ...(group.classId ? { classId: group.classId } : {}),
          ...(group.title ? { title: group.title } : {}),
          ...(group.memberUserIds && group.memberUserIds.length > 0 ? { memberUserIds: [...group.memberUserIds] } : {}),
        },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

export const updateConversation = (conversationId: string, change: S['UpdateConversationRequest']): Promise<GroupAdmin> =>
  request(async () => unwrap(await api.PATCH('/api/v1/conversations/{conversationId}', { ...conversationPath(conversationId), body: change })));

export const removeMember = (conversationId: string, memberId: string, reason: string): Promise<MemberList> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/conversations/{conversationId}/members/{memberId}/remove', { params: { path: { conversationId, memberId } }, body: { reason } })),
  );

export const addMembers = (conversationId: string, userIds: readonly string[]): Promise<MemberList> =>
  request(async () => unwrap(await api.POST('/api/v1/conversations/{conversationId}/members', { ...conversationPath(conversationId), body: { userIds: [...userIds] } })));

export const approveGroup = (conversationId: string): Promise<GroupAdmin> =>
  request(async () => unwrap(await api.POST('/api/v1/conversations/{conversationId}/approve', conversationPath(conversationId))));

export const rejectGroup = (conversationId: string): Promise<GroupAdmin> =>
  request(async () => unwrap(await api.POST('/api/v1/conversations/{conversationId}/reject', conversationPath(conversationId))));
