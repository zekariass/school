package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.moderation.ModerationTarget;
import com.nemaz.school.moderation.ReportTarget;
import com.nemaz.school.moderation.ReviewContent;
import com.nemaz.school.moderation.ReviewContent.Item;
import com.nemaz.school.moderation.TargetRef;
import com.nemaz.school.moderation.TargetTypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The feed's side of the moderation SPIs (docs/Safeguarding_Rules.md S1.1, S2.4, S3): posts, comments and stories.
 * <ul>
 * <li><b>May this reporter see it?</b> The feed's own live predicate ({@link FeedVisibility}: the class and school rules, the
 * guardian rule that hides everything from custody-restricted and revoked guardians), so what a person cannot see they cannot
 * report, and a report of something unseen answers the same generic 404.</li>
 * <li><b>Hide and remove use the existing statuses</b> (post and story HIDDEN, comment HIDDEN) and write the moderation state
 * ({@code moderation_case_id}, {@code moderation_hidden_at}) so that the feed's own un-hide refuses to undo a case and its
 * delete keeps the files. The original text and photos are never deleted.</li>
 * <li><b>The reviewer path</b> returns the reported target with its immediate context (a post with its comments, a story), in
 * any state, hidden and deleted included; the moderation module audits the read.</li>
 * </ul>
 */
@Component
class FeedModeration implements ReportTarget, ModerationTarget {

	private static final Set<String> TYPES = Set.of(TargetTypes.POST, TargetTypes.POST_COMMENT, TargetTypes.STORY);

	private final JdbcClient jdbc;

	private final FeedVisibility visibility;

	private final FeedAccess access;

	private final Clock clock;

	FeedModeration(JdbcClient jdbc, FeedVisibility visibility, FeedAccess access, Clock clock) {
		this.jdbc = jdbc;
		this.visibility = visibility;
		this.access = access;
		this.clock = clock;
	}

	@Override
	public Set<String> targetTypes() {
		return TYPES;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// ReportTarget

	private record Owner(UUID schoolId, UUID authorId, UUID postId) {
	}

	private Optional<Owner> owner(String type, UUID id) {
		String sql = switch (type) {
			case TargetTypes.POST -> "select school_id, author_id, id as post_id from posts where id = :id and deleted_at is null";
			case TargetTypes.POST_COMMENT -> "select p.school_id, c.author_id, p.id as post_id from post_comments c join posts p on p.id = c.post_id where c.id = :id and c.status <> 'DELETED' and p.deleted_at is null";
			case TargetTypes.STORY -> "select school_id, author_id, id as post_id from stories where id = :id and deleted_at is null";
			default -> null;
		};
		if (sql == null) {
			return Optional.empty();
		}
		return this.jdbc.sql(sql)
			.param("id", id)
			.query((rs, i) -> new Owner(Rows.uuid(rs, "school_id"), Rows.uuid(rs, "author_id"), Rows.uuid(rs, "post_id")))
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TargetRef> resolveFor(CurrentUser reporter, String type, UUID id) {
		Optional<Owner> owner = owner(type, id);
		if (owner.isEmpty() || !this.access.featureEnabled(owner.get().schoolId())) {
			return Optional.empty();
		}
		boolean visible = switch (type) {
			case TargetTypes.POST -> this.visibility.canSeePost(reporter, id);
			case TargetTypes.POST_COMMENT -> commentVisible(id) && this.visibility.canSeePost(reporter, owner.get().postId());
			case TargetTypes.STORY -> this.visibility.canSeeStory(reporter, id);
			default -> false;
		};
		return visible ? Optional.of(ref(type, id, owner.get())) : Optional.empty();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TargetRef> describe(String type, UUID id) {
		return owner(type, id).map((o) -> ref(type, id, o));
	}

	private static TargetRef ref(String type, UUID id, Owner owner) {
		UUID context = TargetTypes.POST_COMMENT.equals(type) ? owner.postId() : null;
		return new TargetRef(type, id, owner.schoolId(), owner.authorId(), false, context);
	}

	private boolean commentVisible(UUID commentId) {
		return this.jdbc.sql("select status = 'VISIBLE' from post_comments where id = :id").param("id", commentId).query(Boolean.class).optional().orElse(false);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// ModerationTarget

	private static String table(String type) {
		return switch (type) {
			case TargetTypes.POST -> "posts";
			case TargetTypes.POST_COMMENT -> "post_comments";
			case TargetTypes.STORY -> "stories";
			default -> throw new IllegalArgumentException("Not a feed target: " + type);
		};
	}

	@Override
	@Transactional
	public void hold(String type, UUID id, UUID caseId) {
		this.jdbc.sql("update " + table(type) + " set moderation_case_id = :c where id = :id and moderation_case_id is null")
			.param("c", caseId)
			.param("id", id)
			.update();
	}

	@Override
	@Transactional
	public void takeDown(String type, UUID id, UUID caseId, UUID actorId, boolean removal) {
		Timestamp now = Timestamp.from(this.clock.instant());
		switch (type) {
			case TargetTypes.POST -> {
				int flipped = this.jdbc.sql("""
						update posts set status = 'HIDDEN', is_pinned = false, pinned_at = null, moderation_case_id = :c,
						       moderation_hidden_at = :now, updated_at = :now, version = version + 1
						where id = :id and status = 'PUBLISHED'""")
					.param("c", caseId)
					.param("now", now)
					.param("id", id)
					.update();
				markIfRemoval(type, id, caseId, now, flipped, removal);
			}
			case TargetTypes.POST_COMMENT -> {
				int flipped = this.jdbc.sql("""
						update post_comments set status = 'HIDDEN', moderated_by = :by, moderation_case_id = :c, moderation_hidden_at = :now
						where id = :id and status = 'VISIBLE'""")
					.param("by", actorId)
					.param("c", caseId)
					.param("now", now)
					.param("id", id)
					.update();
				markIfRemoval(type, id, caseId, now, flipped, removal);
			}
			case TargetTypes.STORY -> {
				int flipped = this.jdbc.sql("""
						update stories set status = 'HIDDEN', moderation_case_id = :c, moderation_hidden_at = :now, updated_at = :now,
						       version = version + 1
						where id = :id and status = 'PUBLISHED'""")
					.param("c", caseId)
					.param("now", now)
					.param("id", id)
					.update();
				markIfRemoval(type, id, caseId, now, flipped, removal);
			}
			default -> throw new IllegalArgumentException("Not a feed target: " + type);
		}
	}

	/** A removal is marked even on content that was hidden already, so that nobody but a new decision can show it again. */
	private void markIfRemoval(String type, UUID id, UUID caseId, Timestamp now, int flipped, boolean removal) {
		if (removal && flipped == 0) {
			this.jdbc.sql("update " + table(type) + " set moderation_case_id = :c, moderation_hidden_at = coalesce(moderation_hidden_at, :now) where id = :id")
				.param("c", caseId)
				.param("now", now)
				.param("id", id)
				.update();
		}
	}

	@Override
	@Transactional
	public void restore(String type, UUID id, UUID caseId, UUID actorId) {
		Timestamp now = Timestamp.from(this.clock.instant());
		switch (type) {
			case TargetTypes.POST -> this.jdbc.sql("""
					update posts set status = 'PUBLISHED', moderation_hidden_at = null, updated_at = :now, version = version + 1
					where id = :id and moderation_case_id = :c and moderation_hidden_at is not null and status = 'HIDDEN'""")
				.param("now", now)
				.param("c", caseId)
				.param("id", id)
				.update();
			case TargetTypes.POST_COMMENT -> this.jdbc.sql("""
					update post_comments set status = 'VISIBLE', moderated_by = :by, moderation_hidden_at = null
					where id = :id and moderation_case_id = :c and moderation_hidden_at is not null and status = 'HIDDEN'""")
				.param("by", actorId)
				.param("c", caseId)
				.param("id", id)
				.update();
			case TargetTypes.STORY -> this.jdbc.sql("""
					update stories set status = case when expires_at > :now then 'PUBLISHED' else 'EXPIRED' end,
					       moderation_hidden_at = null, updated_at = :now, version = version + 1
					where id = :id and moderation_case_id = :c and moderation_hidden_at is not null and status = 'HIDDEN'""")
				.param("now", now)
				.param("c", caseId)
				.param("id", id)
				.update();
			default -> throw new IllegalArgumentException("Not a feed target: " + type);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public ReviewContent read(String type, UUID id) {
		List<Item> items = new ArrayList<>();
		switch (type) {
			case TargetTypes.POST -> {
				items.add(postItem(id, true));
				items.addAll(comments(id, null));
			}
			case TargetTypes.POST_COMMENT -> {
				UUID postId = this.jdbc.sql("select post_id from post_comments where id = :id").param("id", id).query(UUID.class).optional().orElse(null);
				if (postId != null) {
					items.add(postItem(postId, false));
					items.addAll(comments(postId, id));
				}
			}
			case TargetTypes.STORY -> this.jdbc.sql("select author_id, author_role, caption, status, media_id, created_at from stories where id = :id")
				.param("id", id)
				.query((rs, i) -> items.add(new Item("STORY", id, Rows.uuid(rs, "author_id"), rs.getString("author_role"),
						rs.getString("caption"), rs.getString("status"),
						(rs.getObject("media_id") != null) ? List.of(Rows.uuid(rs, "media_id")) : List.of(),
						Rows.instant(rs, "created_at"), true)))
				.list();
			default -> throw new IllegalArgumentException("Not a feed target: " + type);
		}
		return ReviewContent.text(items);
	}

	private Item postItem(UUID postId, boolean highlighted) {
		List<UUID> media = this.jdbc.sql("select media_id from post_media where post_id = :p order by sort_order, media_id")
			.param("p", postId)
			.query(UUID.class)
			.list();
		return this.jdbc.sql("select author_id, author_role, body, status, created_at from posts where id = :id")
			.param("id", postId)
			.query((rs, i) -> new Item("POST", postId, Rows.uuid(rs, "author_id"), rs.getString("author_role"), rs.getString("body"),
					rs.getString("status"), media, Rows.instant(rs, "created_at"), highlighted))
			.single();
	}

	private List<Item> comments(UUID postId, UUID reported) {
		return this.jdbc.sql("select id, author_id, author_role, body, status, created_at from post_comments where post_id = :p order by created_at, id limit 200")
			.param("p", postId)
			.query((rs, i) -> new Item("COMMENT", Rows.uuid(rs, "id"), Rows.uuid(rs, "author_id"), rs.getString("author_role"), rs.getString("body"),
					rs.getString("status"), List.of(), Rows.instant(rs, "created_at"), Rows.uuid(rs, "id").equals(reported)))
			.list();
	}

}
