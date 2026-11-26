import type { ConversationSummary, Message } from "./types";

/** Test fixtures: complete API objects with the fields a test does not care about filled in. */
export function message(over: Partial<Message> & { id: string }): Message {
  return {
    conversationId: "c1",
    messageType: "TEXT",
    status: "SENT",
    body: "hello",
    attachments: [],
    reactions: [],
    mine: false,
    sentAt: "2026-11-26T08:00:00Z",
    canEdit: false,
    canDelete: false,
    ...over,
  };
}

export function conversation(over: Partial<ConversationSummary> & { id: string }): ConversationSummary {
  return {
    activityAt: "2026-11-26T08:00:00Z",
    canPost: true,
    muted: false,
    myActingAs: "GUARDIAN",
    myRole: "MEMBER",
    postingPolicy: "ALL_MEMBERS",
    schoolId: "s1",
    status: "ACTIVE",
    type: "DIRECT",
    unreadCount: 0,
    ...over,
  };
}
