import type { ConversationSummary, MessageItem, MessagePage, Participant } from './types';

/** Test fixtures: a minimal valid payload of each shape, overridable. */

export const person = (over: Partial<Participant> = {}): Participant => ({
  memberId: 'mem-1',
  actingAs: 'TEACHER',
  role: 'MEMBER',
  self: false,
  displayName: 'Ms Hanna',
  ...over,
});

export const message = (over: Partial<MessageItem> = {}): MessageItem => ({
  id: 'm1',
  conversationId: 'c1',
  messageType: 'TEXT',
  status: 'SENT',
  body: 'Hello',
  attachments: [],
  reactions: [],
  mine: false,
  sentAt: '2026-11-26T10:00:00Z',
  canEdit: false,
  canDelete: false,
  ...over,
});

export const summary = (over: Partial<ConversationSummary> = {}): ConversationSummary => ({
  id: 'c1',
  type: 'DIRECT',
  schoolId: 's1',
  status: 'ACTIVE',
  postingPolicy: 'ALL',
  myRole: 'MEMBER',
  myActingAs: 'GUARDIAN',
  muted: false,
  unreadCount: 0,
  activityAt: '2026-11-26T10:00:00Z',
  canPost: true,
  ...over,
});

export const page = (items: MessageItem[], nextCursor?: string): MessagePage => ({ items, ...(nextCursor ? { nextCursor } : {}) });
