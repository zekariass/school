package com.nemaz.school.feed.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.FeedScope;
import com.nemaz.school.feed.application.FeedModels.PostStatus;
import com.nemaz.school.feed.application.FeedModels.PostType;
import com.nemaz.school.feed.application.FeedModels.StoryStatus;

/** Database rows of posts and stories as the services read them (no entity classes: hand-written SQL, like engagement). */
final class FeedRows {

	private FeedRows() {
	}

	/** Columns of {@code posts} aliased {@code p}. */
	static final String POST_COLUMNS = """
			p.id, p.scope_type, p.school_id, p.class_id, p.author_id, p.author_role, p.post_type, p.body,
			p.body_language_code, p.is_contribution, p.status, p.scheduled_for, p.published_at, p.decided_by, p.decided_at,
			p.rejection_reason, p.comments_enabled, p.is_pinned, p.edited_at, p.media_confirmed_at, p.publish_error_code,
			p.created_at, p.deleted_at""";

	record PostRow(UUID id, FeedScope scope, UUID schoolId, UUID classId, UUID authorId, FeedAuthorRole authorRole,
			PostType postType, String body, String bodyLanguage, boolean contribution, PostStatus status,
			Instant scheduledFor, Instant publishedAt, UUID decidedBy, Instant decidedAt, String rejectionReason,
			boolean commentsEnabled, boolean pinned, Instant editedAt, Instant mediaConfirmedAt, String publishErrorCode,
			Instant createdAt, Instant deletedAt) {

		boolean published() {
			return this.status == PostStatus.PUBLISHED && this.deletedAt == null;
		}

	}

	static PostRow post(ResultSet rs, int row) throws SQLException {
		return new PostRow(Rows.uuid(rs, "id"), FeedScope.valueOf(rs.getString("scope_type")), Rows.uuid(rs, "school_id"),
				Rows.uuid(rs, "class_id"), Rows.uuid(rs, "author_id"), FeedAuthorRole.valueOf(rs.getString("author_role")),
				PostType.valueOf(rs.getString("post_type")), rs.getString("body"), rs.getString("body_language_code"),
				rs.getBoolean("is_contribution"), PostStatus.valueOf(rs.getString("status")),
				Rows.instant(rs, "scheduled_for"), Rows.instant(rs, "published_at"), Rows.uuid(rs, "decided_by"),
				Rows.instant(rs, "decided_at"), rs.getString("rejection_reason"), rs.getBoolean("comments_enabled"),
				rs.getBoolean("is_pinned"), Rows.instant(rs, "edited_at"), Rows.instant(rs, "media_confirmed_at"),
				rs.getString("publish_error_code"), Rows.instant(rs, "created_at"), Rows.instant(rs, "deleted_at"));
	}

	/** Columns of {@code stories} aliased {@code s}. */
	static final String STORY_COLUMNS = """
			s.id, s.scope_type, s.school_id, s.class_id, s.author_id, s.author_role, s.media_id, s.caption,
			s.caption_language_code, s.status, s.published_at, s.expires_at, s.media_confirmed_at, s.deleted_at""";

	record StoryRow(UUID id, FeedScope scope, UUID schoolId, UUID classId, UUID authorId, FeedAuthorRole authorRole,
			UUID mediaId, String caption, String captionLanguage, StoryStatus status, Instant publishedAt,
			Instant expiresAt, Instant mediaConfirmedAt, Instant deletedAt) {
	}

	static StoryRow story(ResultSet rs, int row) throws SQLException {
		return new StoryRow(Rows.uuid(rs, "id"), FeedScope.valueOf(rs.getString("scope_type")), Rows.uuid(rs, "school_id"),
				Rows.uuid(rs, "class_id"), Rows.uuid(rs, "author_id"), FeedAuthorRole.valueOf(rs.getString("author_role")),
				Rows.uuid(rs, "media_id"), rs.getString("caption"), rs.getString("caption_language_code"),
				StoryStatus.valueOf(rs.getString("status")), Rows.instant(rs, "published_at"),
				Rows.instant(rs, "expires_at"), Rows.instant(rs, "media_confirmed_at"), Rows.instant(rs, "deleted_at"));
	}

}
