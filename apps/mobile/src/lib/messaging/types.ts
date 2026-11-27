import type { components } from '@school/api-client';

type S = components['schemas'];

export type ConversationSummary = S['ConversationSummaryView'];
export type ConversationDetail = S['ConversationView'];
export type ConversationPage = S['CursorPageConversationSummaryView'];
export type MessageItem = S['MessageItemView'];
export type MessagePage = S['CursorPageMessageItemView'];
export type SendResult = S['SendResultView'];
export type Participant = S['ParticipantView'];
export type MemberList = S['MemberListView'];
export type MessagingCapabilities = S['MessagingCapabilitiesView'];
export type Contact = S['ContactView'];
export type ContactLabel = S['ParticipantViewLabel'];
export type GuardianLabel = S['GuardianLabel'];
export type GroupAdmin = S['GroupAdminView'];
export type BlockedPerson = S['BlockView'];
export type Reaction = S['ReactionView'];
export type Attachment = S['AttachmentView'];

export type GroupKind = S['CreateGroupConversationRequest']['kind'];
export type MemberRole = Participant['role'];
export type ActingAs = Participant['actingAs'];
export type RelationshipType = NonNullable<GuardianLabel['relationshipType']>;

/** The twelve reactions the API accepts (a fixed set: free emoji would need content moderation). Same list as the server's. */
export const REACTION_SET = ['👍', '❤️', '😂', '😮', '😢', '🙏', '👏', '🎉', '✅', '❓', '👀', '💯'] as const;

/** Messages a conversation page loads, and pages kept in memory per conversation (older ones need a scroll up again). */
export const MESSAGE_PAGE_SIZE = 30;
