package com.nemaz.school.feed.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.media.MediaKind;
import com.nemaz.school.media.MediaSummary;
import com.nemaz.school.media.MediaVariant;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The vocabulary of the feed API: enums and the response records. Media is always returned as ids and variant names
 * (clients ask {@code GET /api/v1/media/{id}/url?variant=} for a signed link, which the media module authorizes
 * through the owning post or story); no URL ever appears in a payload.
 */
public final class FeedModels {

	private FeedModels() {
	}

	public enum FeedScope {

		CLASS, SCHOOL

	}

	public enum PostStatus {

		DRAFT, PENDING_APPROVAL, SCHEDULED, PUBLISHED, REJECTED, HIDDEN, ARCHIVED

	}

	public enum PostType {

		POST, ACTIVITY, ACHIEVEMENT

	}

	public enum FeedAuthorRole {

		TEACHER, SCHOOL_ADMIN, GUARDIAN, STUDENT

	}

	public enum ReactionType {

		LIKE, HEART, CLAP, STAR

	}

	public enum CommentStatus {

		VISIBLE, HIDDEN, DELETED

	}

	public enum StoryStatus {

		PUBLISHED, HIDDEN, EXPIRED, DELETED

	}

	public enum PublishMode {

		DRAFT, PUBLISH, SCHEDULE

	}

	public enum ReportReason {

		INAPPROPRIATE, PRIVACY, SPAM, OTHER

	}

	@Schema(description = "Who wrote it. displayName (and userId, avatar) are present only when the viewer may know the person: staff names are visible to everybody who sees the post; a guardian or student is named only to the author themself, to the class's teachers and moderators, to the student's own guardians and, for students, to classmates. Everybody else gets the role label only")
	public record FeedAuthor(UUID userId, @NotNull FeedAuthorRole role, String displayName, MediaSummary avatar) {
	}

	@Schema(description = "A READY, unattached upload of purpose POST made for the same class or school")
	public record FeedMediaRef(@NotNull UUID mediaId, @Size(max = 300, message = "validation.tooLong") String caption) {
	}

	public record FeedMediaItem(@NotNull UUID mediaId, int sortOrder, String caption, @NotNull MediaKind kind, Integer width,
			Integer height, @NotNull List<MediaVariant> variants) {
	}

	@Schema(description = "A tagged student, BASIC data only (name and avatar)")
	public record FeedStudentTag(@NotNull UUID studentId, @NotNull String displayName, MediaSummary avatar) {
	}

	public record PostRights(boolean canEdit, boolean canDelete, boolean canModerate, boolean canPin, boolean canComment,
			boolean canPublish) {
	}

	@Schema(description = "A post. Statuses other than PUBLISHED are visible to the author and the moderators only. mediaWithheld is true when photos exist but are not shown to this viewer because a tagged student's media consent is not granted (the author and moderators still see them, with mediaWithheld true as a warning). tags lists only the tagged students this viewer may know")
	public record PostView(@NotNull UUID id, @NotNull FeedScope scope, @NotNull UUID schoolId, UUID classId,
			@NotNull PostStatus status, @NotNull PostType postType, @NotNull FeedAuthor author, String body,
			String bodyLanguageCode, boolean contribution, Instant publishedAt, Instant scheduledFor,
			@NotNull Instant createdAt, Instant editedAt, boolean pinned, boolean commentsEnabled,
			@NotNull List<FeedMediaItem> media, boolean mediaWithheld, @NotNull List<FeedStudentTag> tags,
			int commentCount, @NotNull Map<String, Integer> reactionCounts, ReactionType myReaction,
			@Schema(description = "How many people saw the post (never names); only for the author, the class's teachers and moderators") Integer seenCount,
			String rejectionReason, String publishErrorCode, @NotNull PostRights rights) {
	}

	@Schema(description = "A comment. HIDDEN comments are shown (with their status) to the comment's author and moderators only")
	public record CommentView(@NotNull UUID id, @NotNull UUID postId, @NotNull FeedAuthor author, @NotNull String body,
			String bodyLanguageCode, @NotNull CommentStatus status, @NotNull Instant createdAt, boolean mine,
			boolean canDelete, boolean canHide) {
	}

	@Schema(description = "A story, visible until expiresAt (24 hours after publishing)")
	public record StoryView(@NotNull UUID id, @NotNull FeedScope scope, @NotNull UUID schoolId, UUID classId,
			@NotNull StoryStatus status, @NotNull FeedAuthor author, String caption, String captionLanguageCode,
			FeedMediaItem media, boolean mediaWithheld, @NotNull List<FeedStudentTag> tags, @NotNull Instant publishedAt,
			@NotNull Instant expiresAt, boolean viewedByMe, Integer seenCount, boolean canDelete) {
	}

	public record ReactionSummary(@NotNull Map<String, Integer> counts, ReactionType mine) {
	}

	@Schema(description = "A cached or fresh machine translation. Always labelled: machineTranslated is true")
	public record TranslationView(@NotNull String text, String sourceLanguage, @NotNull String targetLanguage,
			boolean machineTranslated, String provider, boolean cached) {
	}

	@Schema(description = "What the signed-in user may do in the feed of a school or class, for the clients' menus and buttons (the API stays the authority)")
	public record FeedCapabilities(boolean canPost, boolean canPostSchoolWide, boolean canModerate, boolean canContribute,
			boolean contributionNeedsApproval, boolean commentsDefaultEnabled, boolean canComment,
			@NotNull List<MediaKind> contributionMediaKinds, int pinLimit, boolean translationEnabled,
			@Schema(description = "Children of the caller (or the caller) who may be tagged in a contribution of this class") @NotNull List<UUID> contributionStudentIds,
			@Schema(description = "IANA time zone of the class's campus or of the school: clients show and pick scheduled times in it, never in the phone's zone") @NotNull String timeZone) {
	}

}
