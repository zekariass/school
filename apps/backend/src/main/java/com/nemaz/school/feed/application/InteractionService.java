package com.nemaz.school.feed.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople.PersonCard;
import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.feed.ContentReported;
import com.nemaz.school.feed.application.FeedModels.CommentStatus;
import com.nemaz.school.feed.application.FeedModels.CommentView;
import com.nemaz.school.feed.application.FeedModels.FeedAuthorRole;
import com.nemaz.school.feed.application.FeedModels.ReactionSummary;
import com.nemaz.school.feed.application.FeedModels.ReactionType;
import com.nemaz.school.feed.application.FeedModels.ReportReason;
import com.nemaz.school.feed.application.FeedRows.PostRow;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments, reactions, "seen by" and reports on published posts.
 * <ul>
 * <li><b>Comments</b> need a post the caller can see, comments switched on for it (default from
 * {@code feed.comments.default_enabled}) and the caller's relation allowing it ({@link FeedAccess#mayComment}); the
 * author is always the caller's own account. The author sees their own HIDDEN comment (with its status); the moderators
 * (LEAD, class managers, school feed managers) see and can hide or delete any; everybody else sees VISIBLE ones.
 * Authors are named by the privacy rules of {@link FeedViewer}.</li>
 * <li><b>Reactions:</b> one per user and post (the primary key), set or replaced by an upsert, removed idempotently.</li>
 * <li><b>Seen by:</b> a row per viewer; only the count is ever returned, to the author and the class's staff.</li>
 * <li><b>Reports</b> are a stub for M13: the report is written to the audit log and published as
 * {@link ContentReported}; nothing is moderated here.</li>
 * </ul>
 */
@Service
public class InteractionService {

	static final int COMMENTS_PER_HOUR = 60;

	static final int REPORTS_PER_DAY = 20;

	private final JdbcClient jdbc;

	private final FeedAccess access;

	private final FeedVisibility visibility;

	private final PostViews views;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	InteractionService(JdbcClient jdbc, FeedAccess access, FeedVisibility visibility, PostViews views, RateLimiter limiter,
			AuditLog audit, ApplicationEventPublisher events, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.visibility = visibility;
		this.views = views;
		this.limiter = limiter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
	}

	public record CommentCommand(String body, String bodyLanguageCode) {
	}

	/** The published post if the caller may see it, else 404 (interactions exist only on published posts). */
	private PostRow visiblePost(CurrentUser user, UUID schoolId, UUID postId) {
		this.access.requireSchool(user, schoolId);
		PostRow row = this.views.find(schoolId, postId);
		if (!row.published() || !this.visibility.canSeePost(user, postId)) {
			throw ApiException.notFound("feed.post_not_found");
		}
		return row;
	}

	// --- comments --------------------------------------------------------------------------

	@Transactional
	public CommentView comment(CurrentUser user, UUID schoolId, UUID postId, CommentCommand cmd) {
		PostRow post = visiblePost(user, schoolId, postId);
		if (!post.commentsEnabled()) {
			throw ApiException.forbidden("feed.comments_disabled");
		}
		if (!this.access.mayComment(user, post)) {
			throw ApiException.forbidden("feed.comment_not_allowed");
		}
		this.limiter.consume("feed-comment-user", user.userId().toString(), COMMENTS_PER_HOUR, Duration.ofHours(1),
				"feed.rate_limited");
		String body = (cmd.body() == null) ? "" : cmd.body().strip();
		if (body.isEmpty() || body.length() > 1000) {
			throw ApiException.badRequest("feed.comment_empty");
		}
		String language = FeedMedia.blankToNull(cmd.bodyLanguageCode());
		if (language != null && !this.jdbc.sql("select exists (select 1 from languages where code = :c)").param("c", language).query(Boolean.class).single()) {
			throw ApiException.badRequest("feed.translation_language_invalid");
		}
		UUID id = Ids.newId();
		FeedAuthorRole role = this.access.commentRole(user, post);
		this.jdbc.sql("""
				insert into post_comments (id, post_id, school_id, author_id, author_role, body, body_language_code, status, created_at)
				values (:id, :post, :school, :author, :role, :body, :lang, 'VISIBLE', :now)""")
			.param("id", id)
			.param("post", postId)
			.param("school", schoolId)
			.param("author", user.userId())
			.param("role", role.name())
			.param("body", body)
			.param("lang", language)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return mapComments(user, post, List.of(findComment(postId, id))).getFirst();
	}

	@Transactional(readOnly = true)
	public CursorPage<CommentView> comments(CurrentUser user, UUID schoolId, UUID postId, String cursor, Integer limit) {
		this.access.requireSchool(user, schoolId);
		PostRow post = this.views.requireReadable(user, schoolId, postId);
		FeedViewer viewer = new FeedViewer(user, this.access);
		boolean moderator = viewer.moderates(post);
		int size = Cursors.clampLimit(limit);
		UUID after = null;
		if (cursor != null) {
			try {
				after = UUID.fromString(Cursors.decode(cursor, 1).getFirst());
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		List<CommentRow> rows = this.jdbc.sql("""
				select id, post_id, author_id, author_role, body, body_language_code, status, created_at from post_comments
				where post_id = :post and (status = 'VISIBLE' or (status = 'HIDDEN' and (:moderator or author_id = :user)))
				  and (cast(:after as uuid) is null or id > cast(:after as uuid))
				order by id limit :n""")
			.param("post", postId)
			.param("moderator", moderator)
			.param("user", user.userId())
			.param("after", after)
			.param("n", size + 1)
			.query(InteractionService::commentRow)
			.list();
		CursorPage<CommentRow> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(r.id().toString()));
		return new CursorPage<>(mapComments(user, post, page.items()), page.nextCursor());
	}

	@Transactional
	public void deleteComment(CurrentUser user, UUID schoolId, UUID postId, UUID commentId) {
		this.access.requireSchool(user, schoolId);
		PostRow post = this.views.requireReadable(user, schoolId, postId);
		CommentRow comment = findComment(postId, commentId);
		boolean author = comment.authorId().equals(user.userId());
		boolean moderator = this.access.moderates(user, post);
		if (comment.status() == CommentStatus.DELETED) {
			throw ApiException.notFound("feed.comment_not_found");
		}
		if (!author && !moderator) {
			throw (comment.status() == CommentStatus.VISIBLE) ? ApiException.forbidden("feed.access_denied")
					: ApiException.notFound("feed.comment_not_found");
		}
		this.jdbc.sql("update post_comments set status = 'DELETED', moderated_by = :m where id = :id")
			.param("m", author ? null : user.userId())
			.param("id", commentId)
			.update();
		this.audit.record(AuditEntry.of("feed.comment.delete", AuditOutcome.SUCCESS)
			.target("comment", commentId)
			.school(schoolId)
			.metadata("postId", postId.toString())
			.metadata("moderation", !author));
	}

	@Transactional
	public CommentView hideComment(CurrentUser user, UUID schoolId, UUID postId, UUID commentId, boolean hidden) {
		this.access.requireSchool(user, schoolId);
		PostRow post = this.views.requireReadable(user, schoolId, postId);
		if (!this.access.moderates(user, post)) {
			throw ApiException.forbidden("feed.access_denied");
		}
		CommentRow comment = findComment(postId, commentId);
		if (comment.status() == CommentStatus.DELETED) {
			throw ApiException.notFound("feed.comment_not_found");
		}
		if (!hidden && this.jdbc.sql("select moderation_hidden_at is not null from post_comments where id = :id")
			.param("id", commentId)
			.query(Boolean.class)
			.single()) {
			// hidden by a moderation case: only the case can restore it
			throw ApiException.conflict("moderation.content_held");
		}
		this.jdbc.sql("update post_comments set status = :status, moderated_by = :m where id = :id")
			.param("status", hidden ? "HIDDEN" : "VISIBLE")
			.param("m", user.userId())
			.param("id", commentId)
			.update();
		this.audit.record(AuditEntry.of(hidden ? "feed.comment.hide" : "feed.comment.unhide", AuditOutcome.SUCCESS)
			.target("comment", commentId)
			.school(schoolId)
			.metadata("postId", postId.toString())
			.metadata("authorId", comment.authorId().toString()));
		return mapComments(user, post, List.of(findComment(postId, commentId))).getFirst();
	}

	private record CommentRow(UUID id, UUID postId, UUID authorId, FeedAuthorRole role, String body, String language,
			CommentStatus status, java.time.Instant createdAt) {
	}

	private static CommentRow commentRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new CommentRow(Rows.uuid(rs, "id"), Rows.uuid(rs, "post_id"), Rows.uuid(rs, "author_id"),
				FeedAuthorRole.valueOf(rs.getString("author_role")), rs.getString("body"), rs.getString("body_language_code"),
				CommentStatus.valueOf(rs.getString("status")), Rows.instant(rs, "created_at"));
	}

	private CommentRow findComment(UUID postId, UUID commentId) {
		return this.jdbc.sql("""
				select id, post_id, author_id, author_role, body, body_language_code, status, created_at from post_comments
				where id = :id and post_id = :post""")
			.param("id", commentId)
			.param("post", postId)
			.query(InteractionService::commentRow)
			.optional()
			.orElseThrow(() -> ApiException.notFound("feed.comment_not_found"));
	}

	private List<CommentView> mapComments(CurrentUser user, PostRow post, List<CommentRow> rows) {
		FeedViewer viewer = new FeedViewer(user, this.access);
		boolean moderator = viewer.moderates(post);
		Set<UUID> people = new HashSet<>();
		for (CommentRow row : rows) {
			if (viewer.canSeeName(row.role(), row.authorId(), post.scope(), post.schoolId(), post.classId())) {
				people.add(row.authorId());
			}
		}
		Map<UUID, PersonCard> cards = this.access.people().cards(people);
		List<CommentView> result = new ArrayList<>();
		for (CommentRow row : rows) {
			boolean mine = row.authorId().equals(user.userId());
			result.add(new CommentView(row.id(), row.postId(),
					PostViews.author(viewer, row.role(), row.authorId(), post.scope(), post.schoolId(), post.classId(), cards),
					row.body(), row.language(), row.status(), row.createdAt(), mine,
					(mine || moderator) && row.status() != CommentStatus.DELETED,
					moderator && row.status() == CommentStatus.VISIBLE));
		}
		return result;
	}

	// --- reactions -------------------------------------------------------------------------

	@Transactional
	public ReactionSummary react(CurrentUser user, UUID schoolId, UUID postId, ReactionType reaction) {
		visiblePost(user, schoolId, postId);
		this.jdbc.sql("""
				insert into post_reactions (post_id, user_id, reaction, created_at) values (:post, :user, :reaction, :now)
				on conflict (post_id, user_id) do update set reaction = excluded.reaction""")
			.param("post", postId)
			.param("user", user.userId())
			.param("reaction", reaction.name())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		return summary(user, postId);
	}

	@Transactional
	public ReactionSummary unreact(CurrentUser user, UUID schoolId, UUID postId) {
		visiblePost(user, schoolId, postId);
		this.jdbc.sql("delete from post_reactions where post_id = :post and user_id = :user")
			.param("post", postId)
			.param("user", user.userId())
			.update();
		return summary(user, postId);
	}

	private ReactionSummary summary(CurrentUser user, UUID postId) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		this.jdbc.sql("select reaction, count(*) as n from post_reactions where post_id = :post group by reaction order by reaction")
			.param("post", postId)
			.query((rs, i) -> {
				counts.put(rs.getString("reaction"), rs.getInt("n"));
				return 0;
			})
			.list();
		ReactionType mine = this.jdbc.sql("select reaction from post_reactions where post_id = :post and user_id = :user")
			.param("post", postId)
			.param("user", user.userId())
			.query((rs, i) -> ReactionType.valueOf(rs.getString("reaction")))
			.optional()
			.orElse(null);
		return new ReactionSummary(counts, mine);
	}

	// --- seen by ---------------------------------------------------------------------------

	@Transactional
	public void markSeen(CurrentUser user, UUID schoolId, UUID postId) {
		PostRow post = visiblePost(user, schoolId, postId);
		if (post.authorId().equals(user.userId())) {
			return;
		}
		this.jdbc.sql("""
				insert into post_views (post_id, user_id, viewed_at) values (:post, :user, :now)
				on conflict (post_id, user_id) do nothing""")
			.param("post", postId)
			.param("user", user.userId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
	}

	// --- reports (stub for M13) ------------------------------------------------------------

	@Transactional
	public void report(CurrentUser user, UUID schoolId, UUID postId, ReportReason reason) {
		visiblePost(user, schoolId, postId);
		this.limiter.consume("feed-report-user", user.userId().toString(), REPORTS_PER_DAY, Duration.ofDays(1),
				"feed.report_rate_limited");
		this.audit.record(AuditEntry.of("feed.post.report", AuditOutcome.SUCCESS)
			.target("post", postId)
			.school(schoolId)
			.metadata("reason", reason.name()));
		this.events.publishEvent(new ContentReported(Ids.newId(), "POST", postId, schoolId, user.userId(), reason.name()));
	}

}
