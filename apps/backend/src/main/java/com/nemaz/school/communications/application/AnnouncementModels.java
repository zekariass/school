package com.nemaz.school.communications.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The vocabulary of the announcements API: enums and the request and response records. Names are unique across the
 * code base (they become OpenAPI schema names).
 */
public final class AnnouncementModels {

	private AnnouncementModels() {
	}

	/**
	 * NORMAL, IMPORTANT (may ask for an acknowledgement), URGENT and SAFETY (need {@code school.announcements.urgent};
	 * they ignore quiet hours and cannot be muted).
	 */
	public enum AnnouncementType {

		NORMAL, IMPORTANT, URGENT, SAFETY;

		public boolean urgent() {
			return this == URGENT || this == SAFETY;
		}

		public boolean mayRequireAcknowledgement() {
			return this != NORMAL;
		}

	}

	public enum AnnouncementStatus {

		DRAFT, SCHEDULED, PUBLISHED, WITHDRAWN

	}

	public enum AudienceType {

		SCHOOL, CAMPUS, GRADE_LEVEL, CLASS, USER

	}

	public enum RecipientRole {

		GUARDIAN, STUDENT, STAFF

	}

	/** IN_APP is always on; PUSH is the default; EMAIL and SMS are optional and switched by school settings. */
	public enum AnnouncementChannel {

		IN_APP, PUSH, EMAIL, SMS

	}

	public enum PublishMode {

		DRAFT, PUBLISH, SCHEDULE

	}

	public enum InboxFilter {

		ALL, UNREAD, NEEDS_ACKNOWLEDGEMENT

	}

	public enum InboxSort {

		NEWEST, UNREAD_FIRST

	}

	public enum RecipientFilter {

		ALL, UNREAD, UNACKNOWLEDGED, ACKNOWLEDGED, FAILED

	}

	public enum NoticeKind {

		UPDATED, REMINDER, WITHDRAWN

	}

	// --- requests ---------------------------------------------------------------------------

	/**
	 * One audience. {@code type} says which of the ids applies: SCHOOL none, CAMPUS {@code campusId}, GRADE_LEVEL
	 * {@code gradeLevelId}, CLASS {@code classId}, USER {@code userIds} (people the author may already see). {@code roles}
	 * defaults to GUARDIAN; STUDENT only when the school allows announcements to students; STAFF only for school
	 * announcement managers.
	 */
	public record AnnouncementAudienceInput(@NotNull AudienceType type, UUID campusId, UUID gradeLevelId, UUID classId,
			@Size(max = 200) List<UUID> userIds, Set<RecipientRole> roles) {

		public AnnouncementAudienceInput {
			userIds = (userIds == null) ? List.of() : List.copyOf(userIds);
			roles = (roles == null || roles.isEmpty()) ? Set.of(RecipientRole.GUARDIAN) : Set.copyOf(roles);
		}

	}

	/** A translation written by a person (never machine translated, decision D5). */
	public record AnnouncementTranslation(@NotNull @Size(min = 1, max = 200) String title,
			@NotNull @Size(min = 1, max = 5000) String body) {
	}

	public record AnnouncementRequest(@NotNull @Size(max = 400) String title, @NotNull @Size(max = 10000) String body,
			@Size(max = 16) String bodyLanguageCode, @Size(max = 10) Map<String, AnnouncementTranslation> translations,
			AnnouncementType type, Boolean requiresAcknowledgement, Set<AnnouncementChannel> channels,
			@NotNull @Size(min = 1, max = 20) List<AnnouncementAudienceInput> audiences, PublishMode mode,
			Instant scheduledFor) {
	}

	/**
	 * What to change; a null field stays as it is. After publishing only title, body, translations and language can change
	 * (the audience, type, channels and acknowledgement requirement are fixed: withdraw and send a new one instead).
	 * {@code material} is the editor's own call: true tells the recipients ("updated"), false is a silent correction
	 * (typo); it counts for published announcements only.
	 */
	public record AnnouncementUpdateRequest(@Size(max = 400) String title, @Size(max = 10000) String body,
			@Size(max = 16) String bodyLanguageCode, @Size(max = 10) Map<String, AnnouncementTranslation> translations,
			AnnouncementType type, Boolean requiresAcknowledgement, Set<AnnouncementChannel> channels,
			@Size(min = 1, max = 20) List<AnnouncementAudienceInput> audiences, Boolean material,
			@Size(max = 300) String changeNote) {
	}

	public record AnnouncementScheduleRequest(@NotNull Instant scheduledFor) {
	}

	public record AnnouncementWithdrawRequest(@Size(max = 500) String reason) {
	}

	public record CommunicationChannelsRequest(Boolean email, Boolean sms) {
	}

	// --- responses --------------------------------------------------------------------------

	public record AnnouncementAudienceView(@NotNull AudienceType type, UUID campusId, UUID gradeLevelId, UUID classId,
			@Schema(description = "Present for USER audiences; hidden from everybody but the author and managers") UUID userId,
			@NotNull Set<RecipientRole> roles) {
	}

	/** The caller's own relation to the announcement. */
	public record AnnouncementMine(@NotNull boolean read, Instant readAt, @NotNull boolean acknowledged,
			Instant acknowledgedAt, Integer acknowledgedRevision,
			@Schema(description = "True when the caller was in the recipient snapshot at publish (false: opens it through the school's list only)") @NotNull boolean recipient) {
	}

	/** What the caller may do with this announcement (menus and buttons; the API stays the authority). */
	public record AnnouncementAbilities(@NotNull boolean edit, @NotNull boolean publish, @NotNull boolean schedule,
			@NotNull boolean withdraw, @NotNull boolean delete, @NotNull boolean viewReport, @NotNull boolean remind) {
	}

	/**
	 * {@code body} is plain text, always (never markup: show it as text and nothing else; making links clickable is the
	 * client's job and only for http and https). A WITHDRAWN announcement keeps its title but shows its body to the author
	 * and managers only. {@code audiences}, {@code recipientCount}, {@code channels} and delivery state are for the
	 * author and managers.
	 */
	public record AnnouncementView(@NotNull UUID id, @NotNull UUID schoolId, @NotNull String schoolName, @NotNull UUID authorId,
			String authorName, @NotNull AnnouncementType type, @NotNull AnnouncementStatus status, @NotNull String title,
			String body, @NotNull String bodyFormat, String bodyLanguageCode,
			@NotNull Map<String, AnnouncementTranslation> translations, @NotNull boolean requiresAcknowledgement,
			List<AnnouncementChannel> channels, List<AnnouncementAudienceView> audiences, Instant scheduledFor,
			String scheduleErrorCode, Instant publishedAt, @NotNull int revision, Instant editedAt, Instant withdrawnAt,
			String withdrawalReason, Integer recipientCount, Boolean deliveryComplete, @NotNull Instant createdAt,
			@NotNull AnnouncementMine mine, @NotNull AnnouncementAbilities can) {
	}

	/** One line of a list. */
	public record AnnouncementCard(@NotNull UUID id, @NotNull UUID schoolId, @NotNull String schoolName,
			@NotNull UUID authorId, String authorName, @NotNull AnnouncementType type, @NotNull AnnouncementStatus status,
			@NotNull String title, String excerpt, @NotNull boolean requiresAcknowledgement, Instant scheduledFor,
			Instant publishedAt, Instant withdrawnAt, @NotNull int revision, @NotNull Instant createdAt,
			Instant readAt, Instant acknowledgedAt, Integer recipientCount) {
	}

	public record AnnouncementRevisionView(@NotNull int revision, @NotNull String title, @NotNull String body,
			@NotNull Map<String, AnnouncementTranslation> translations, @NotNull boolean material, String changeNote,
			@NotNull UUID editedBy, String editedByName, @NotNull Instant editedAt) {
	}

	public record AnnouncementCapabilities(@NotNull boolean canCompose, @NotNull boolean schoolWide,
			@NotNull boolean urgentAllowed, @NotNull boolean studentsEnabled, @NotNull boolean emailAvailable,
			@NotNull boolean smsAvailable, @NotNull List<AnnouncementType> smsTypes,
			@NotNull List<AnnouncementClassRef> classes, @NotNull int reminderMinHours) {
	}

	public record AnnouncementClassRef(@NotNull UUID id, @NotNull String name) {
	}

	public record AnnouncementPreview(@NotNull int recipients, @NotNull int guardians, @NotNull int students,
			@NotNull int staff) {
	}

	public record AnnouncementUnreadSummary(@NotNull int unread, @NotNull int needingAcknowledgement) {
	}

	/** Counts of the report; see {@code AnnouncementReports} for exactly what each number means. */
	public record AnnouncementReportSummary(@NotNull UUID announcementId, @NotNull AnnouncementStatus status,
			@NotNull int recipients, @NotNull int delivered, @NotNull int failed, @NotNull int read,
			@NotNull int acknowledged, @NotNull boolean requiresAcknowledgement, @NotNull boolean deliveryComplete,
			@NotNull ChannelCounts push, @NotNull ChannelCounts email, @NotNull ChannelCounts sms,
			@Schema(description = "People outside the recipient snapshot who opened it (linked or enrolled after publishing)") @NotNull int lateReaders,
			Instant lastReminderAt, @NotNull int reminders) {
	}

	/** {@code skipped}: not attempted (not wanted, no device or contact, opted out, over a cap, switched off). */
	public record ChannelCounts(@NotNull int sent, @NotNull int failed, @NotNull int skipped, @NotNull int pending) {
	}

	public record AnnouncementRecipientView(@NotNull UUID userId, @NotNull String displayName, @NotNull RecipientRole role,
			UUID viaStudentId, String viaStudentName, String maskedPhone, String maskedEmail, Instant notifiedAt,
			@NotNull String push, @NotNull String email, @NotNull String sms, Instant readAt, Instant acknowledgedAt,
			Integer acknowledgedRevision) {
	}

	public record AnnouncementReminderView(@NotNull UUID noticeId, @NotNull int targeted, @NotNull Instant requestedAt) {
	}

	public record CommunicationChannelsView(@NotNull boolean email, @NotNull boolean sms,
			@Schema(description = "True when this number opted out with the SMS provider and only the provider can undo it") @NotNull boolean smsBlockedByProvider) {
	}

}
