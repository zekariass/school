package com.nemaz.school.portfolio.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import com.nemaz.school.portfolio.PortfolioCommentPosted;
import com.nemaz.school.portfolio.application.PortfolioModels.ItemRow;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioAuthorRole;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioCommentView;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioStatus;
import com.nemaz.school.portfolio.application.PortfolioModels.PortfolioVisibility;
import com.nemaz.school.portfolio.application.PortfolioViews.Relation;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encouraging comments on approved, guardian-visible items (rule book P5). Written by the student, the student's own guardians
 * (setting) and the item's reviewers; read by the same three groups only, however wide the item's audience is. Guardians and
 * students appear by role label only. A reviewer hides a comment (a tombstone: no text except for the author and the reviewers); the
 * author deletes their own (the text is erased). The text is never in the audit log, events or notifications.
 */
@Service
public class PortfolioCommentService {

	static final int COMMENTS_PER_HOUR = 30;

	private final JdbcClient jdbc;

	private final PortfolioAccess access;

	private final PortfolioAudience audience;

	private final PortfolioViews views;

	private final PortfolioSettings settings;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final RateLimiter limiter;

	private final Clock clock;

	PortfolioCommentService(JdbcClient jdbc, PortfolioAccess access, PortfolioAudience audience, PortfolioViews views,
			PortfolioSettings settings, AuditLog audit, ApplicationEventPublisher events, RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.audience = audience;
		this.views = views;
		this.settings = settings;
		this.audit = audit;
		this.events = events;
		this.limiter = limiter;
		this.clock = clock;
	}

	private record Ctx(ItemRow item, Relation relation) {
	}

	private Ctx context(CurrentUser user, UUID schoolId, UUID itemId) {
		this.access.requireSchool(user, schoolId);
		ItemRow item = this.audience.find(user, itemId).filter((i) -> i.schoolId().equals(schoolId)).orElseThrow(() -> ApiException.notFound("portfolio.item_not_found"));
		return new Ctx(item, this.views.relations(user, List.of(item)).getFirst());
	}

	@Transactional
	public PortfolioCommentView create(CurrentUser user, UUID schoolId, UUID itemId, String rawBody) {
		Ctx c = context(user, schoolId, itemId);
		Relation rel = c.relation();
		if (!rel.readsComments()) {
			throw ApiException.forbidden("portfolio.comments_not_allowed");
		}
		ItemRow item = c.item();
		PortfolioVisibility effective = this.settings.effective(item.visibility(), schoolId);
		if (item.status() != PortfolioStatus.APPROVED || effective.rank() < PortfolioVisibility.GUARDIANS.rank()) {
			throw ApiException.conflict("portfolio.comments_not_available");
		}
		PortfolioAuthorRole role = rel.reviewer() ? PortfolioAuthorRole.TEACHER
				: rel.ownGuardian() ? PortfolioAuthorRole.GUARDIAN : PortfolioAuthorRole.STUDENT;
		if (role == PortfolioAuthorRole.GUARDIAN && !this.settings.guardianComments(schoolId)) {
			throw ApiException.forbidden("portfolio.comments_disabled");
		}
		this.limiter.consume("portfolio-comment", user.userId().toString(), COMMENTS_PER_HOUR, Duration.ofHours(1), "portfolio.rate_limited");
		String body = PortfolioText.required(rawBody, PortfolioText.COMMENT_MAX, "comment");
		UUID id = Ids.newId();
		this.jdbc.sql("""
				insert into portfolio_comments (id, item_id, school_id, author_id, author_role, body, created_at)
				values (:id, :item, :school, :author, :role, :body, :now)""")
			.param("id", id)
			.param("item", itemId)
			.param("school", schoolId)
			.param("author", user.userId())
			.param("role", role.name())
			.param("body", body)
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		this.audit.record(AuditEntry.of("portfolio.comment.create", AuditOutcome.SUCCESS)
			.target("portfolio_comment", id)
			.school(schoolId)
			.metadata("itemId", itemId.toString())
			.metadata("authorRole", role.name()));
		this.events.publishEvent(new PortfolioCommentPosted(id, itemId, schoolId, user.userId()));
		return one(user, c, id);
	}

	/** The comments of an item, oldest first (the thread reads downwards), for the student, their own guardians and the reviewers. */
	@Transactional(readOnly = true)
	public CursorPage<PortfolioCommentView> list(CurrentUser user, UUID schoolId, UUID itemId, String cursor, Integer limit) {
		Ctx c = context(user, schoolId, itemId);
		if (!c.relation().readsComments()) {
			throw ApiException.forbidden("portfolio.comments_not_allowed");
		}
		int size = Cursors.clampLimit(limit);
		UUID after = (cursor == null) ? null : parse(cursor);
		List<Row> rows = this.jdbc.sql("""
				select id, author_id, author_role, body, status, created_at from portfolio_comments
				where item_id = :item and status <> 'DELETED' and (cast(:after as uuid) is null or id > cast(:after as uuid))
				order by id limit :limit""")
			.param("item", itemId)
			.param("after", after)
			.param("limit", size + 1)
			.query(PortfolioCommentService::row)
			.list();
		CursorPage<Row> page = CursorPage.fromOverfetch(rows, size, (r) -> Cursors.encode(r.id().toString()));
		Map<UUID, PersonCard> cards = namesOf(page.items());
		return page.map((r) -> toView(user, c, r, cards));
	}

	@Transactional
	public PortfolioCommentView hide(CurrentUser user, UUID schoolId, UUID itemId, UUID commentId, String rawReason) {
		Ctx c = context(user, schoolId, itemId);
		if (!c.relation().reviewer()) {
			throw ApiException.forbidden("portfolio.review_not_allowed");
		}
		Row comment = find(itemId, commentId);
		String reason = PortfolioText.required(rawReason, PortfolioText.REASON_MAX, "reason");
		if (comment.status().equals("VISIBLE")) {
			this.jdbc.sql("""
					update portfolio_comments set status = 'HIDDEN', hide_reason = :reason, moderated_by = :user, moderated_at = :t
					where id = :id""")
				.param("reason", reason)
				.param("user", user.userId())
				.param("t", Timestamp.from(this.clock.instant()))
				.param("id", commentId)
				.update();
			this.audit.record(AuditEntry.of("portfolio.comment.hide", AuditOutcome.SUCCESS)
				.target("portfolio_comment", commentId)
				.school(schoolId)
				.metadata("itemId", itemId.toString())
				.metadata("reasonLength", PortfolioText.optional(reason, PortfolioText.REASON_MAX, "reason").length()));
		}
		return one(user, c, commentId);
	}

	@Transactional
	public void delete(CurrentUser user, UUID schoolId, UUID itemId, UUID commentId) {
		Ctx c = context(user, schoolId, itemId);
		if (!c.relation().readsComments()) {
			throw ApiException.forbidden("portfolio.comments_not_allowed");
		}
		Row comment = find(itemId, commentId);
		if (!comment.authorId().equals(user.userId())) {
			throw ApiException.forbidden("portfolio.comment_delete_not_allowed");
		}
		this.jdbc.sql("update portfolio_comments set status = 'DELETED', body = null where id = :id").param("id", commentId).update();
		this.audit.record(AuditEntry.of("portfolio.comment.delete", AuditOutcome.SUCCESS)
			.target("portfolio_comment", commentId)
			.school(schoolId)
			.metadata("itemId", itemId.toString()));
	}

	// --- helpers -----------------------------------------------------------------------------------

	private record Row(UUID id, UUID authorId, PortfolioAuthorRole role, String body, String status, java.time.Instant createdAt) {
	}

	private static Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Row(Rows.uuid(rs, "id"), Rows.uuid(rs, "author_id"), PortfolioAuthorRole.valueOf(rs.getString("author_role")),
				rs.getString("body"), rs.getString("status"), Rows.instant(rs, "created_at"));
	}

	private Row find(UUID itemId, UUID commentId) {
		return this.jdbc.sql("""
				select id, author_id, author_role, body, status, created_at from portfolio_comments
				where id = :id and item_id = :item and status <> 'DELETED'""")
			.param("id", commentId)
			.param("item", itemId)
			.query(PortfolioCommentService::row)
			.optional()
			.orElseThrow(() -> ApiException.notFound("portfolio.comment_not_found"));
	}

	private PortfolioCommentView one(CurrentUser user, Ctx c, UUID commentId) {
		Row r = find(c.item().id(), commentId);
		return toView(user, c, r, namesOf(List.of(r)));
	}

	/** Reviewers are named; guardians and students never are. */
	private Map<UUID, PersonCard> namesOf(List<Row> rows) {
		List<UUID> teachers = rows.stream().filter((r) -> r.role() == PortfolioAuthorRole.TEACHER).map(Row::authorId).distinct().toList();
		return this.access.people().cards(teachers);
	}

	private PortfolioCommentView toView(CurrentUser user, Ctx c, Row r, Map<UUID, PersonCard> cards) {
		boolean mine = r.authorId().equals(user.userId());
		boolean hidden = r.status().equals("HIDDEN");
		boolean showText = !hidden || mine || c.relation().reviewer();
		PersonCard card = cards.get(r.authorId());
		return new PortfolioCommentView(r.id(), r.role(), (r.role() == PortfolioAuthorRole.TEACHER && card != null) ? card.displayName() : null,
				showText ? r.body() : null, r.status(), mine, c.relation().reviewer() && r.status().equals("VISIBLE"),
				mine, r.createdAt());
	}

	private static UUID parse(String cursor) {
		try {
			return UUID.fromString(Cursors.decode(cursor, 1).getFirst());
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("invalid_cursor");
		}
	}

}
