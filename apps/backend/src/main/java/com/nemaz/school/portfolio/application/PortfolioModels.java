package com.nemaz.school.portfolio.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.media.MediaVariant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Enumerations, requests and views of the portfolio API. Schema names are prefixed {@code Portfolio} (OpenAPI needs unique names). */
public final class PortfolioModels {

	private PortfolioModels() {
	}

	public enum PortfolioStatus {

		DRAFT, SUBMITTED, CHANGES_REQUESTED, APPROVED, REJECTED, ARCHIVED

	}

	/** The audience of an item, from narrowest to widest. */
	public enum PortfolioVisibility {

		STUDENT_TEACHER, GUARDIANS, CLASS, SCHOOL;

		public int rank() {
			return ordinal();
		}

		public static PortfolioVisibility least(PortfolioVisibility a, PortfolioVisibility b) {
			return (a.rank() <= b.rank()) ? a : b;
		}

		public static PortfolioVisibility ofRank(int rank) {
			return values()[Math.max(0, Math.min(values().length - 1, rank))];
		}

	}

	@Schema(description = "AUDIO and VIDEO are reserved: they are refused with 422 portfolio.item_type_unsupported until the media pipeline can process them safely")
	public enum PortfolioItemType {

		TEXT, PHOTO, DRAWING, SCAN, DOCUMENT, AUDIO, VIDEO

	}

	public enum PortfolioMode {

		DRAFT, SUBMIT

	}

	public enum PortfolioDecision {

		APPROVE, REQUEST_CHANGES, REJECT

	}

	/** How an author of a comment is shown to others: never a name for guardians and students. */
	public enum PortfolioAuthorRole {

		TEACHER, GUARDIAN, STUDENT

	}

	/** Why the files of an item are withheld from a viewer (internal; clients see {@code mediaWithheld}). */
	public enum Withheld {

		NONE, OWN_CONSENT, OTHERS

	}

	// --- requests ------------------------------------------------------------------------------

	@Schema(description = "A student's new item about their own work. Text fields are plain text (control and invisible characters are removed). mode DRAFT (default) saves privately, SUBMIT sends it for review")
	public record PortfolioItemRequest(@NotNull PortfolioItemType itemType, @NotNull @Size(max = 2000) String title,
			@Size(max = 4000) String description, @Size(max = 4000) String reflection, @Size(max = 20000) String textContent,
			LocalDate workDate, UUID classId, UUID subjectId, PortfolioVisibility visibility,
			@Size(max = 20) List<UUID> mediaIds, PortfolioMode mode, Boolean showsOtherChildren,
			@Size(max = 40) List<UUID> taggedStudentIds, Boolean confirmNoUnconsentedStudents) {
	}

	@Schema(description = "A teacher's quick capture for a student of the class: the item is approved at once. showsOtherChildren defaults to TRUE when omitted (restrictive); with files and no tagged student, confirmNoUnconsentedStudents must be true")
	public record PortfolioQuickCaptureRequest(@NotNull UUID studentId, @NotNull PortfolioItemType itemType,
			@NotNull @Size(max = 2000) String title, @Size(max = 4000) String description, @Size(max = 4000) String reflection,
			@Size(max = 20000) String textContent, LocalDate workDate, UUID subjectId, PortfolioVisibility visibility,
			@Size(max = 20) List<UUID> mediaIds, Boolean showsOtherChildren, @Size(max = 40) List<UUID> taggedStudentIds,
			Boolean confirmNoUnconsentedStudents) {
	}

	@Schema(description = "Edits a DRAFT or CHANGES_REQUESTED item (its student only). A null field is unchanged; an empty text clears it; mediaIds replaces the files")
	public record PortfolioItemUpdateRequest(@Size(max = 2000) String title, @Size(max = 4000) String description,
			@Size(max = 4000) String reflection, @Size(max = 20000) String textContent, LocalDate workDate, UUID classId,
			UUID subjectId, PortfolioVisibility visibility, @Size(max = 20) List<UUID> mediaIds, Boolean showsOtherChildren,
			@Size(max = 40) List<UUID> taggedStudentIds, Boolean confirmNoUnconsentedStudents) {
	}

	@Schema(description = "A reviewer's decision. REQUEST_CHANGES and REJECT need a note (shown to the student, never to guardians). visibility may only lower the item's audience")
	public record PortfolioReviewRequest(@NotNull PortfolioDecision decision, @Size(max = 2000) String note,
			PortfolioVisibility visibility) {
	}

	@Schema(description = "An optional note for archive, unarchive and similar actions")
	public record PortfolioNoteRequest(@Size(max = 2000) String note) {
	}

	@Schema(description = "A short encouraging comment (plain text, 1 to 500 characters)")
	public record PortfolioCommentRequest(@NotNull @Size(max = 2000) String body) {
	}

	@Schema(description = "Why a comment is hidden (audited, 1 to 200 characters)")
	public record PortfolioHideRequest(@NotNull @Size(max = 1000) String reason) {
	}

	// --- views ---------------------------------------------------------------------------------

	@Schema(description = "A student as classmates and families may know them: BASIC data only (never date of birth, gender or username)")
	public record PortfolioStudentView(@NotNull UUID studentId, String displayName, MediaSummary avatar,
			Map<String, Object> avatarConfig) {
	}

	@Schema(description = "One file of an item: ids and variant names, never URLs (ask GET /media/{id}/url for a signed link)")
	public record PortfolioMediaView(@NotNull UUID mediaId, int sortOrder, @NotNull MediaKind kind, @NotNull String mimeType,
			Integer width, Integer height, @NotNull List<MediaVariant> variants) {
	}

	public record PortfolioTaggedStudent(@NotNull UUID studentId, String displayName) {
	}

	@Schema(description = "What the caller may do with the item now; the server decides, clients only show or hide controls")
	public record PortfolioItemCapabilities(boolean canEdit, boolean canSubmit, boolean canDelete, boolean canReview,
			boolean canArchive, boolean canUnarchive, boolean canFeature, boolean canComment, boolean canShareToStory) {
	}

	@Schema(description = "One portfolio item as the caller may see it. mediaWithheld: the item has files that are hidden from the caller because media consent is missing. reviewNote is for the student and reviewers only")
	public record PortfolioItemView(@NotNull UUID id, @NotNull UUID schoolId, @NotNull PortfolioStudentView student, UUID classId,
			UUID subjectId, @NotNull PortfolioItemType itemType, String title, String description, String reflection,
			String textContent, LocalDate workDate, @NotNull PortfolioStatus status, @NotNull PortfolioVisibility visibility,
			@NotNull PortfolioVisibility effectiveVisibility, boolean featured, @NotNull PortfolioAuthorRole createdByRole,
			Instant createdAt, Instant submittedAt, Instant reviewedAt, @NotNull List<PortfolioMediaView> media,
			boolean mediaWithheld, boolean showsOtherChildren, boolean mediaConfirmed, int taggedStudentCount,
			List<PortfolioTaggedStudent> taggedStudents, String reviewNote, int commentCount,
			@NotNull PortfolioItemCapabilities capabilities) {
	}

	@Schema(description = "A row of the status history (student and reviewers only). Reviewers are named, the student is not")
	public record PortfolioHistoryEntry(@NotNull UUID id, @NotNull String action, String fromStatus, String toStatus,
			@NotNull PortfolioAuthorRole actorRole, String actorName, String note, @NotNull Instant createdAt) {
	}

	@Schema(description = "A comment. Guardians and students appear by role label only (no name, no id); mine marks the caller's own. A hidden comment is a tombstone without text")
	public record PortfolioCommentView(@NotNull UUID id, @NotNull PortfolioAuthorRole authorRole, String authorName,
			String body, @NotNull String status, boolean mine, boolean canHide, boolean canDelete, @NotNull Instant createdAt) {
	}

	@Schema(description = "What the caller may do in the school's portfolios")
	public record PortfolioCapabilities(boolean studentMayCreate, boolean canCapture, @NotNull List<UUID> captureClassIds,
			boolean reviewer, @NotNull PortfolioVisibility maxVisibility, boolean guardianComments,
			boolean shareToStoryAvailable, @NotNull List<PortfolioItemType> itemTypes,
			@NotNull List<PortfolioItemType> unsupportedItemTypes) {
	}

	// --- internal ------------------------------------------------------------------------------

	/** An item row (internal). */
	public record ItemRow(UUID id, UUID portfolioId, UUID schoolId, UUID studentId, UUID classId, UUID subjectId, UUID createdBy,
			PortfolioItemType itemType, String title, String description, String reflection, String textContent,
			LocalDate workDate, PortfolioStatus status, PortfolioVisibility visibility, boolean featured,
			boolean showsOthers, Instant confirmedAt, UUID confirmedBy, Instant createdAt, Instant submittedAt,
			Instant reviewedAt, Instant archivedAt) {

		public boolean hasConfirmation() {
			return this.confirmedAt != null;
		}

	}

}
