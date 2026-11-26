package com.nemaz.school.messaging.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.ConversationType;
import com.nemaz.school.messaging.domain.DirectKind;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.domain.MemberRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * What the messaging API returns. Names are unique across the OpenAPI document. <b>Privacy</b> (rule book R2, R6, R12): a person
 * in a conversation is described by {@link ParticipantView}: teachers, staff and students by name; a guardian only by a role
 * label ("Abebe's mother") that depends on who looks; nobody's date of birth, gender, username, phone or e-mail is ever
 * returned, and people are identified by their <em>member id in that conversation</em>, never by user id.
 */
public final class MessagingModels {

	private MessagingModels() {
	}

	@Schema(description = "A person in a conversation. displayName is present for teachers, staff, students and for yourself; a guardian is never named to anybody else: guardian carries the role label instead. memberId is valid inside this conversation only")
	public record ParticipantView(@NotNull UUID memberId, @NotNull ActingAs actingAs, @NotNull MemberRole role,
			@NotNull boolean self, String displayName, GuardianLabel guardian) {
	}

	@Schema(description = "How a guardian is shown to others: relationshipType and studentGivenName are each present only when the viewer may know them (the child's teachers see \"Abebe's mother\"; other guardians see neither; a student sees the relationship of their own guardians)")
	public record GuardianLabel(RelationshipType relationshipType, String studentGivenName) {
	}

	public record ContextStudentView(@NotNull UUID studentId, String displayName) {
	}

	public record LastMessageView(@NotNull UUID messageId, @NotNull String messageType, @NotNull String status,
			@Schema(description = "First 120 characters; absent for deleted and hidden messages (tombstones) and for images") String preview,
			Instant sentAt, ParticipantView sender) {
	}

	public record ConversationSummaryView(@NotNull UUID id, @NotNull ConversationType type, DirectKind directKind,
			GroupKind groupKind, @NotNull UUID schoolId, UUID classId, String title,
			@Schema(description = "The other person of a DIRECT conversation") ParticipantView counterpart,
			@NotNull String status, @NotNull String postingPolicy, @NotNull MemberRole myRole, @NotNull ActingAs myActingAs,
			@Schema(description = "The child this conversation is about or, for a guardian, who they are acting for: group the inbox by it. Absent for students except themselves") ContextStudentView contextStudent,
			UUID onBehalfOfStudentId, @NotNull boolean muted, Instant mutedUntil, @NotNull int unreadCount,
			LastMessageView lastMessage, @NotNull Instant activityAt,
			@Schema(description = "DIRECT conversations with an adult and a student: whether a guardian can see it (students are told \"your parent or guardian can see this conversation\")") Boolean guardiansCanSee,
			@NotNull boolean canPost) {
	}

	public record ConversationView(@NotNull ConversationSummaryView summary, @NotNull boolean canModerate,
			@NotNull Instant createdAt) {
	}

	@Schema(description = "A message as the caller may see it. Deleted and hidden messages are tombstones: status DELETED or HIDDEN with no body, attachments or reactions")
	public record MessageItemView(@NotNull UUID id, @NotNull UUID conversationId, ParticipantView sender,
			@NotNull String messageType, @NotNull String status, String body, String bodyLanguageCode, String systemCode,
			UUID replyToMessageId, @NotNull List<AttachmentView> attachments, @NotNull List<ReactionView> reactions,
			@NotNull boolean mine, Instant sentAt, Instant editedAt, Instant scheduledFor,
			@Schema(description = "Only on your own messages of a DIRECT conversation: when the other person first read it") Instant seenAt,
			@NotNull boolean canEdit, @NotNull boolean canDelete) {
	}

	public record AttachmentView(@NotNull UUID mediaId, @NotNull int sortOrder, MediaSummary media) {
	}

	public record ReactionView(@NotNull String emoji, @NotNull int count, @NotNull boolean mine) {
	}

	public record SendResultView(@NotNull MessageItemView message,
			@Schema(description = "false when this clientMessageId was already stored: the original message is returned") @NotNull boolean created,
			@Schema(description = "The recipient teacher is outside office hours: the message is delivered without a push notification") @NotNull boolean afterHours) {
	}

	public record ContactView(@NotNull UUID userId, ParticipantViewLabel label, UUID conversationId) {
	}

	@Schema(description = "A person you may start a conversation with: teachers by name; guardians by label only")
	public record ParticipantViewLabel(@NotNull ActingAs actingAs, String displayName, GuardianLabel guardian,
			String teacherRole, String className, String subjectName) {
	}

	@Schema(description = "A machine translation of one message, always labelled: machineTranslated is true (unless the text is already in the target language)")
	public record MessageTranslationView(@NotNull String text, String sourceLanguage, @NotNull String targetLanguage,
			@NotNull boolean machineTranslated, @NotNull boolean cached) {
	}

	public record BlockView(@NotNull UUID id, UUID conversationId, ParticipantView person, @NotNull Instant createdAt) {
	}

	@Schema(description = "A group as moderators and proposers see it: metadata only, never messages")
	public record GroupAdminView(@NotNull UUID id, @NotNull ConversationType type, @NotNull GroupKind groupKind, UUID classId,
			String title, @NotNull String status, @NotNull String postingPolicy, @NotNull int memberCount,
			@NotNull boolean proposedByMe, @NotNull Instant createdAt) {
	}

	public record MemberListView(@NotNull List<ParticipantView> members, @NotNull int observerCount) {
	}

	public record MessagingCapabilitiesView(@NotNull boolean featureEnabled, @NotNull boolean canStartParentTeacher,
			@NotNull boolean canStartStudentDirect,
			@Schema(description = "A teacher may start a DIRECT conversation with a student of their class (messaging.student.teacher_direct.enabled): the student's guardians then observe it, or the conversation is admin-visible")
			@NotNull boolean canStartTeacherStudent,
			@Schema(description = "A guardian may start a DIRECT conversation with a guardian of a classmate of their child (messaging.parent_parent.enabled)")
			@NotNull boolean canStartParentParent, @NotNull List<GroupKind> creatableGroups,
			@NotNull boolean canProposeStudentGroup, @NotNull boolean canModerate, @NotNull boolean canReview,
			@NotNull int editWindowMinutes, @NotNull int maxMessageLength, @NotNull boolean studentMediaEnabled,
			@NotNull boolean translationEnabled, OfficeHoursView officeHours,
			@Schema(description = "The school's time zone (IANA id): scheduled messages are picked and shown in it, never in the device's") @NotNull String timeZone,
			@Schema(description = "A teacher may start a DIRECT conversation with a student of their class (messaging.student.teacher_direct.enabled); the student's guardians then become read-only observers") @NotNull boolean canStartTeacherStudent) {
	}

	public record OfficeHoursView(@NotNull List<Integer> days, @NotNull String start, @NotNull String end,
			@NotNull String timeZone) {
	}

	// --- review (R5b, R13) ---------------------------------------------------------------------------------------

	public record ReviewConversationView(@NotNull UUID id, @NotNull ConversationType type, DirectKind directKind,
			GroupKind groupKind, @NotNull boolean adminVisible, @NotNull int openReports, @NotNull List<ParticipantView> participants,
			Instant lastMessageAt, @NotNull Instant createdAt) {
	}

	public record ReviewedMessageView(@NotNull UUID id, ParticipantView sender, @NotNull String messageType,
			@NotNull String status, @Schema(description = "The original text, also of deleted messages") String body,
			@NotNull List<EditView> history, @NotNull List<UUID> attachmentMediaIds, UUID replyToMessageId, Instant sentAt,
			@NotNull int reportCount, String hideReason) {
	}

	public record EditView(@NotNull String kind, @NotNull String body, @NotNull Instant at) {
	}

	public record ReviewedPageView(@NotNull List<ReviewedMessageView> messages, String nextCursor) {
	}

	public record ReportView(@NotNull UUID id, @NotNull UUID messageId, @NotNull UUID conversationId, @NotNull String reason,
			@NotNull String status, @NotNull Instant createdAt) {
	}

	public record StudentBlockView(@NotNull UUID id, @NotNull UUID blockerId, @NotNull UUID blockedId, UUID conversationId,
			@NotNull Instant createdAt) {
	}

}
