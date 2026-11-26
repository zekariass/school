import type { components } from "@school/api-client";

type S = components["schemas"];

export type ConversationSummary = S["ConversationSummaryView"];
export type ConversationDetail = S["ConversationView"];
export type Participant = S["ParticipantView"];
export type MemberList = S["MemberListView"];
export type Message = S["MessageItemView"];
export type SendResult = S["SendResultView"];
export type Capabilities = S["MessagingCapabilitiesView"];
export type Contact = S["ContactView"];
export type ContactLabel = S["ParticipantViewLabel"];
export type GroupAdmin = S["GroupAdminView"];
export type CreateGroupRequest = S["CreateGroupConversationRequest"];
export type Block = S["BlockView"];
export type ReviewConversation = S["ReviewConversationView"];
export type ReviewedPage = S["ReviewedPageView"];
export type ReviewedMessage = S["ReviewedMessageView"];
export type MessageReport = S["ReportView"];
export type StudentBlock = S["StudentBlockView"];
export type Translation = S["MessageTranslationView"];
export type GroupKind = NonNullable<S["ConversationSummaryView"]["groupKind"]>;
export type ReportReason = S["ReportMessageRequest"]["reason"];

/** The fixed set of reactions the API accepts (`MessageService.REACTIONS`); the API refuses any other. */
export const REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🙏", "👏", "🎉", "✅", "❓", "👀", "💯"] as const;

export const REPORT_REASONS = ["INAPPROPRIATE", "BULLYING", "PRIVACY", "SAFETY", "SPAM", "OTHER"] as const satisfies readonly ReportReason[];

export const POSTING_POLICIES = ["ALL_MEMBERS", "MODERATORS_ONLY"] as const;
export const CONVERSATION_STATUSES = ["ACTIVE", "LOCKED", "ARCHIVED"] as const;
