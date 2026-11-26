package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.pagination.CursorPage;
import com.nemaz.school.common.pagination.Cursors;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.messaging.application.MessagingModels.EditView;
import com.nemaz.school.messaging.application.MessagingModels.ParticipantView;
import com.nemaz.school.messaging.application.MessagingModels.ReportView;
import com.nemaz.school.messaging.application.MessagingModels.ReviewConversationView;
import com.nemaz.school.messaging.application.MessagingModels.ReviewedMessageView;
import com.nemaz.school.messaging.application.MessagingModels.ReviewedPageView;
import com.nemaz.school.messaging.application.MessagingModels.StudentBlockView;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.MessageText;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.system.RateLimiter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The audited review</b> (rule book R5b, R13). Reviewers (holders of {@code school.messaging.review}) are never members of
 * a conversation: they read the two kinds of conversation that need a person outside it, through endpoints that make every access
 * leave a trace.
 * <ul>
 * <li>Reviewable: a safeguarded (adult-student DIRECT) conversation with no eligible observer (live), and any conversation
 * with an open report.</li>
 * <li>{@link #read}: needs a reason of 10 to 300 characters, is rate limited (30 an hour per reviewer) and writes the audit
 * entry {@code messaging.review.read} (reviewer, conversation, reason, message count; never text). The page contains the whole
 * history: deleted originals, hidden messages, earlier versions of edited ones. This is the <em>only</em> place where those
 * originals can be read.</li>
 * <li>Listing the reviewable conversations is audited too ({@code messaging.review.list}, counts only).</li>
 * </ul>
 * Review does not depend on the {@code messaging} feature flag: a school cannot switch off its safeguarding.
 */
@Service
public class ReviewService {

	static final int READS_PER_HOUR = 30;

	static final int LISTS_PER_HOUR = 120;

	private final JdbcClient jdbc;

	private final Eligibility eligibility;

	private final SchoolGate gate;

	private final ParticipantViews participants;

	private final AuditLog audit;

	private final RateLimiter limiter;

	ReviewService(JdbcClient jdbc, Eligibility eligibility, SchoolGate gate, ParticipantViews participants, AuditLog audit,
			RateLimiter limiter) {
		this.jdbc = jdbc;
		this.eligibility = eligibility;
		this.gate = gate;
		this.participants = participants;
		this.audit = audit;
		this.limiter = limiter;
	}

	private void requireReviewer(CurrentUser user, UUID schoolId) {
		this.gate.require(user, schoolId, Permissions.SCHOOL_MESSAGING_REVIEW);
	}

	@Transactional
	public CursorPage<ReviewConversationView> conversations(CurrentUser user, UUID schoolId, String cursor, Integer limit) {
		requireReviewer(user, schoolId);
		this.limiter.consume("messaging-review-list", user.userId().toString(), LISTS_PER_HOUR, Duration.ofHours(1),
				"messaging.review_rate_limited");
		int size = Cursors.clampLimit(limit);
		Instant cursorAt = null;
		UUID cursorId = null;
		if (cursor != null) {
			List<String> parts = Cursors.decode(cursor, 2);
			try {
				cursorAt = Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS);
				cursorId = UUID.fromString(parts.get(1));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		record Row(ConversationRow conv, boolean adminVisible, int reports) {
		}
		List<Row> rows = this.eligibility.statement(List.of(schoolId), """
				select %s, %s as admin_visible,
				       (select count(*) from message_reports r where r.conversation_id = c.id and r.status = 'OPEN') as reports
				from conversations c join sp on sp.school_id = c.school_id
				where c.school_id = :s and %s
				  and (cast(:cursor_at as timestamptz) is null or (c.activity_at, c.id) < (cast(:cursor_at as timestamptz), cast(:cursor_id as uuid)))
				order by c.activity_at desc, c.id desc limit :n""".formatted(MessagingRows.CONVERSATION_COLUMNS,
				Eligibility.effectiveAdminVisible(), Eligibility.reviewable()))
			.param("s", schoolId)
			.param("cursor_at", (cursorAt != null) ? Timestamp.from(cursorAt) : null)
			.param("cursor_id", cursorId)
			.param("n", size + 1)
			.query((rs, i) -> new Row(MessagingRows.conversation(rs), rs.getBoolean("admin_visible"), rs.getInt("reports")))
			.list();
		boolean more = rows.size() > size;
		List<Row> page = more ? rows.subList(0, size) : rows;
		List<ReviewConversationView> items = new ArrayList<>();
		for (Row row : page) {
			items.add(new ReviewConversationView(row.conv().id(), row.conv().type(), row.conv().directKind(),
					row.conv().groupKind(), row.adminVisible(), row.reports(), participantsOf(user, row.conv().id()),
					row.conv().lastMessageAt(), row.conv().createdAt()));
		}
		this.audit.record(AuditEntry.of("messaging.review.list", AuditOutcome.SUCCESS)
			.school(schoolId)
			.metadata("count", items.size()));
		String next = more ? Cursors.encode(String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, page.getLast().conv().activityAt())),
				page.getLast().conv().id().toString()) : null;
		return new CursorPage<>(items, next);
	}

	private List<ParticipantView> participantsOf(CurrentUser reviewer, UUID conversationId) {
		List<UUID> ids = this.jdbc.sql("""
				select id from conversation_members where conversation_id = :c and left_at is null
				order by (member_role = 'OBSERVER'), joined_at, id""")
			.param("c", conversationId)
			.query(UUID.class)
			.list();
		Map<UUID, ParticipantView> views = this.participants.forMembers(this.participants.viewerOf(reviewer.userId(), ActingAs.STAFF), ids);
		return ids.stream().map(views::get).toList();
	}

	/** The history of a reviewable conversation, newest first, with a mandatory reason; audited, rate limited. */
	@Transactional
	public ReviewedPageView read(CurrentUser user, UUID schoolId, UUID conversationId, String reason, String cursor,
			Integer limit) {
		requireReviewer(user, schoolId);
		String clean = (reason == null) ? "" : MessageText.clean(reason);
		if (clean.length() < 10) {
			throw ApiException.badRequest("messaging.review_reason_required");
		}
		if (clean.length() > 300) {
			throw ApiException.badRequest("messaging.reason_too_long");
		}
		ConversationRow conv = this.jdbc.sql("select " + MessagingRows.CONVERSATION_COLUMNS
				+ " from conversations c where c.id = :c and c.school_id = :s")
			.param("c", conversationId)
			.param("s", schoolId)
			.query((rs, i) -> MessagingRows.conversation(rs))
			.optional()
			.orElseThrow(MessagingPolicy::notFound);
		boolean reviewable = this.eligibility.statement(List.of(schoolId), """
				select exists (select 1 from conversations c join sp on sp.school_id = c.school_id
				               where c.id = :c and %s)""".formatted(Eligibility.reviewable()))
			.param("c", conversationId)
			.query(Boolean.class)
			.single();
		if (!reviewable) {
			throw ApiException.forbidden("messaging.not_reviewable");
		}
		this.limiter.consume("messaging-review-read", user.userId().toString(), READS_PER_HOUR, Duration.ofHours(1),
				"messaging.review_rate_limited");
		int size = Cursors.clampLimit(limit);
		Long before = null;
		if (cursor != null) {
			try {
				before = Long.parseLong(Cursors.decode(cursor, 1).getFirst());
			}
			catch (NumberFormatException ex) {
				throw ApiException.badRequest("invalid_cursor");
			}
		}
		record Row(UUID id, long seq, UUID senderMember, String type, String status, String body, UUID reply, Instant sentAt,
				String hideReason, int reports) {
		}
		List<Row> rows = this.jdbc.sql("""
				select m.id, m.seq, m.sender_member_id, m.message_type, m.status, m.body, m.reply_to_message_id, m.sent_at, m.hide_reason,
				       (select count(*) from message_reports r where r.message_id = m.id) as reports
				from messages m
				where m.conversation_id = :c and m.seq is not null and m.status in ('SENT', 'EDITED', 'DELETED', 'HIDDEN')
				  and (cast(:before as bigint) is null or m.seq < :before)
				order by m.seq desc limit :n""")
			.param("c", conversationId)
			.param("before", before)
			.param("n", size + 1)
			.query((rs, i) -> new Row(Rows.uuid(rs, "id"), rs.getLong("seq"), Rows.uuid(rs, "sender_member_id"),
					rs.getString("message_type"), rs.getString("status"), rs.getString("body"), Rows.uuid(rs, "reply_to_message_id"),
					Rows.instant(rs, "sent_at"), rs.getString("hide_reason"), rs.getInt("reports")))
			.list();
		boolean more = rows.size() > size;
		List<Row> page = more ? rows.subList(0, size) : rows;
		Set<UUID> memberIds = new LinkedHashSet<>();
		page.forEach((r) -> memberIds.add(r.senderMember()));
		Map<UUID, ParticipantView> people = this.participants
			.forMembers(this.participants.viewerOf(user.userId(), ActingAs.STAFF), memberIds);
		Map<UUID, List<EditView>> history = new LinkedHashMap<>();
		Map<UUID, List<UUID>> media = new LinkedHashMap<>();
		if (!page.isEmpty()) {
			List<UUID> ids = page.stream().map(Row::id).toList();
			this.jdbc.sql("select message_id, kind, body, edited_at from message_edits where message_id in (:ids) order by edited_at, id")
				.param("ids", ids)
				.query((rs, i) -> {
					history.computeIfAbsent(Rows.uuid(rs, "message_id"), (k) -> new ArrayList<>())
						.add(new EditView(rs.getString("kind"), rs.getString("body"), Rows.instant(rs, "edited_at")));
					return 0;
				})
				.list();
			this.jdbc.sql("select message_id, media_id from message_attachments where message_id in (:ids) order by sort_order")
				.param("ids", ids)
				.query((rs, i) -> {
					media.computeIfAbsent(Rows.uuid(rs, "message_id"), (k) -> new ArrayList<>()).add(Rows.uuid(rs, "media_id"));
					return 0;
				})
				.list();
		}
		List<ReviewedMessageView> messages = new ArrayList<>();
		for (Row r : page) {
			List<EditView> edits = history.getOrDefault(r.id(), List.of());
			String body = r.body();
			if (body == null && "DELETED".equals(r.status())) {
				body = edits.stream().filter((e) -> "DELETE".equals(e.kind())).map(EditView::body).findFirst().orElse(null);
			}
			messages.add(new ReviewedMessageView(r.id(), people.get(r.senderMember()), r.type(), r.status(), body, edits,
					media.getOrDefault(r.id(), List.of()), r.reply(), r.sentAt(), r.reports(), r.hideReason()));
		}
		this.audit.record(AuditEntry.of("messaging.review.read", AuditOutcome.SUCCESS)
			.target("conversation", conversationId)
			.school(schoolId)
			.metadata("reviewerId", user.userId().toString())
			.metadata("reason", clean)
			.metadata("messages", messages.size())
			.metadata("firstPage", cursor == null)
			.metadata("kind", (conv.direct()) ? conv.directKind().name() : conv.groupKind().name()));
		return new ReviewedPageView(messages, more ? Cursors.encode(String.valueOf(page.getLast().seq())) : null);
	}

	/** Reports of the school (ids and reason codes, no text and no names), newest first. */
	@Transactional(readOnly = true)
	public List<ReportView> reports(CurrentUser user, UUID schoolId, String status) {
		requireReviewer(user, schoolId);
		return this.jdbc.sql("""
				select id, message_id, conversation_id, reason, status, created_at from message_reports
				where school_id = :s and (cast(:status as text) is null or status = :status)
				order by created_at desc, id desc limit 200""")
			.param("s", schoolId)
			.param("status", status)
			.query((rs, i) -> new ReportView(Rows.uuid(rs, "id"), Rows.uuid(rs, "message_id"), Rows.uuid(rs, "conversation_id"),
					rs.getString("reason"), rs.getString("status"), Rows.instant(rs, "created_at")))
			.list();
	}

	/** Blocks of adults by students of the school (ids only), for safeguarding leads (rule book R7.4). */
	@Transactional(readOnly = true)
	public List<StudentBlockView> studentBlocks(CurrentUser user, UUID schoolId) {
		requireReviewer(user, schoolId);
		return this.jdbc.sql("""
				select b.id, b.blocker_id, b.blocked_id, b.via_conversation_id, b.created_at from user_blocks b
				where b.school_id = :s and b.blocker_is_student
				  and not exists (select 1 from school_students ss where ss.school_id = :s and ss.student_id = b.blocked_id)
				order by b.created_at desc, b.id desc limit 200""")
			.param("s", schoolId)
			.query((rs, i) -> new StudentBlockView(Rows.uuid(rs, "id"), Rows.uuid(rs, "blocker_id"), Rows.uuid(rs, "blocked_id"),
					Rows.uuid(rs, "via_conversation_id"), Rows.instant(rs, "created_at")))
			.list();
	}

}
