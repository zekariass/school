package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.messaging.MessageReported;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.domain.MessageText;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.system.RateLimiter;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hiding messages and reporting them (rule book R11). Moderators hide with a reason (audited), nobody edits other people's
 * messages. A report writes one row, one audit entry and the event {@link MessageReported}; all of them carry ids and the
 * reason <em>code</em> only: never the text of the message or the reporter's note.
 *
 * <p>
 * <b>Who may hide:</b> the owners and moderators of a group, holders of {@code school.messaging.moderate} and managers of its
 * class (for conversations that are not DIRECT), and holders of {@code school.messaging.review} for conversations they may
 * review (admin-visible or reported), DIRECT ones included.
 */
@Service
public class ModerationService {

	static final int REPORTS_PER_DAY = 20;

	public static final List<String> REPORT_REASONS = List.of("INAPPROPRIATE", "BULLYING", "PRIVACY", "SAFETY", "SPAM", "OTHER");

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final Eligibility eligibility;

	private final MessageQueries queries;

	private final SchoolGate gate;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final ApplicationEventPublisher events;

	private final NotificationService notifications;

	private final TenantDirectory directory;

	private final Doorbell doorbell;

	private final Clock clock;

	ModerationService(JdbcClient jdbc, MessagingPolicy policy, Eligibility eligibility, MessageQueries queries,
			SchoolGate gate, AuditLog audit, RateLimiter limiter, ApplicationEventPublisher events,
			NotificationService notifications, TenantDirectory directory, Doorbell doorbell, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.eligibility = eligibility;
		this.queries = queries;
		this.gate = gate;
		this.audit = audit;
		this.limiter = limiter;
		this.events = events;
		this.notifications = notifications;
		this.directory = directory;
		this.doorbell = doorbell;
		this.clock = clock;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// hide

	@Transactional
	public void hide(CurrentUser user, UUID conversationId, UUID messageId, String reason) {
		ConversationRow conv = requireHideAuthority(user, conversationId);
		String clean = (reason == null) ? "" : MessageText.clean(reason);
		if (clean.isEmpty()) {
			throw ApiException.badRequest("messaging.reason_required");
		}
		if (clean.length() > 300) {
			throw ApiException.badRequest("messaging.reason_too_long");
		}
		String status = status(messageId, conversationId);
		if ("HIDDEN".equals(status)) {
			return;
		}
		if (!"SENT".equals(status) && !"EDITED".equals(status)) {
			throw ApiException.conflict("messaging.message_unavailable");
		}
		this.jdbc.sql("update messages set status = 'HIDDEN', hidden_at = :t, hidden_by = :u, hide_reason = :r where id = :m")
			.param("t", Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS)))
			.param("u", user.userId())
			.param("r", clean)
			.param("m", messageId)
			.update();
		this.audit.record(AuditEntry.of("messaging.message.hide", AuditOutcome.SUCCESS)
			.target("message", messageId)
			.school(conv.schoolId())
			.metadata("conversationId", conversationId.toString())
			.metadata("reason", clean));
		this.doorbell.ringAfterCommit(conv.schoolId(), conversationId, messageId, "message.updated");
	}

	@Transactional
	public void unhide(CurrentUser user, UUID conversationId, UUID messageId) {
		ConversationRow conv = requireHideAuthority(user, conversationId);
		String status = status(messageId, conversationId);
		if (!"HIDDEN".equals(status)) {
			return;
		}
		if (this.jdbc.sql("select moderation_hidden_at is not null from messages where id = :m")
			.param("m", messageId)
			.query(Boolean.class)
			.single()) {
			// hidden by a moderation case: only the case can restore it
			throw ApiException.conflict("moderation.content_held");
		}
		this.jdbc.sql("""
				update messages set status = case when edited_at is null then 'SENT' else 'EDITED' end,
				       hidden_at = null, hidden_by = null, hide_reason = null where id = :m""")
			.param("m", messageId)
			.update();
		this.audit.record(AuditEntry.of("messaging.message.unhide", AuditOutcome.SUCCESS)
			.target("message", messageId)
			.school(conv.schoolId())
			.metadata("conversationId", conversationId.toString()));
		this.doorbell.ringAfterCommit(conv.schoolId(), conversationId, messageId, "message.updated");
	}

	private String status(UUID messageId, UUID conversationId) {
		return this.jdbc.sql("select status from messages where id = :m and conversation_id = :c and seq is not null")
			.param("m", messageId)
			.param("c", conversationId)
			.query(String.class)
			.optional()
			.orElseThrow(() -> ApiException.notFound("messaging.message_not_found"));
	}

	private ConversationRow requireHideAuthority(CurrentUser user, UUID conversationId) {
		ConversationRow conv = this.policy.conversation(conversationId).orElseThrow(MessagingPolicy::notFound);
		MessagingPolicy.Moderation moderation = this.policy.tryModeration(user, conversationId);
		if (moderation != null) {
			return moderation.conversation();
		}
		if (this.gate.can(user, conv.schoolId(), Permissions.SCHOOL_MESSAGING_REVIEW) && reviewable(conv)) {
			return conv;
		}
		// the same answers as for any other attempt to moderate: members may know they lack the right, others see nothing
		boolean member = this.policy.find(user.userId(), conversationId, false).isPresent();
		throw member ? ApiException.forbidden("messaging.moderation_not_allowed") : MessagingPolicy.notFound();
	}

	/** Whether reviewers may read the conversation: admin-visible (live) or with an open report. */
	boolean reviewable(ConversationRow conv) {
		return this.eligibility.statement(List.of(conv.schoolId()), """
				select exists (select 1 from conversations c join sp on sp.school_id = c.school_id
				               where c.id = :c and %s)""".formatted(Eligibility.reviewable()))
			.param("c", conv.id())
			.query(Boolean.class)
			.single();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// report

	/** Reports a message of a conversation the caller may use (observers too); answers the same on a repeat. */
	@Transactional
	public void report(CurrentUser user, UUID conversationId, UUID messageId, String reason, String note) {
		Access access = this.policy.access(user, conversationId);
		if (reason == null || !REPORT_REASONS.contains(reason)) {
			throw ApiException.badRequest("messaging.report_reason_invalid");
		}
		String cleanNote = (note == null) ? null : MessageText.clean(note);
		if (cleanNote != null && cleanNote.length() > 500) {
			throw ApiException.badRequest("messaging.report_note_too_long");
		}
		// the reporter must be able to see the message (a tombstone cannot be reported, nor one hidden by their block)
		MessageItemView seen = this.queries.view(user, access, messageId);
		if (seen.mine()) {
			throw ApiException.conflict("messaging.cannot_report_own");
		}
		if (!"SENT".equals(seen.status()) && !"EDITED".equals(seen.status())) {
			throw ApiException.conflict("messaging.message_unavailable");
		}
		this.limiter.consume("messaging-report", user.userId().toString(), REPORTS_PER_DAY, Duration.ofDays(1),
				"messaging.report_rate_limited");
		UUID reportId = Ids.newId();
		int inserted = this.jdbc.sql("""
				insert into message_reports (id, message_id, conversation_id, school_id, reporter_id, reason, note)
				values (:id, :m, :c, :s, :u, :reason, :note) on conflict (message_id, reporter_id) do nothing""")
			.param("id", reportId)
			.param("m", messageId)
			.param("c", conversationId)
			.param("s", access.conversation().schoolId())
			.param("u", user.userId())
			.param("reason", reason)
			.param("note", (cleanNote == null || cleanNote.isEmpty()) ? null : cleanNote)
			.update();
		if (inserted == 0) {
			return;
		}
		UUID schoolId = access.conversation().schoolId();
		this.audit.record(AuditEntry.of("messaging.message.report", AuditOutcome.SUCCESS)
			.target("message", messageId)
			.school(schoolId)
			.metadata("reportId", reportId.toString())
			.metadata("conversationId", conversationId.toString())
			.metadata("reason", reason));
		this.events.publishEvent(new MessageReported(reportId, messageId, conversationId, schoolId, user.userId(), reason));
		notifyAboutReport(reportId, access, user.userId());
	}

	/**
	 * A report that came through the moderation module's generic endpoint ({@code POST /moderation/reports}): the same record
	 * as {@link #report} (so that the audited review path can read the conversation of a reported message) but no event, since
	 * moderation already holds the report. The reporter's details are NOT copied here. Idempotent per (message, reporter).
	 */
	@Transactional
	public void recordExternalReport(UUID reportId, UUID reporterId, UUID messageId, String reason) {
		UUID conversationId = this.jdbc.sql("select conversation_id from messages where id = :m")
			.param("m", messageId)
			.query(UUID.class)
			.optional()
			.orElse(null);
		if (conversationId == null) {
			return;
		}
		Access access = this.policy.find(reporterId, conversationId, false).orElse(null);
		if (access == null) {
			return;
		}
		UUID schoolId = access.conversation().schoolId();
		int inserted = this.jdbc.sql("""
				insert into message_reports (id, message_id, conversation_id, school_id, reporter_id, reason)
				values (:id, :m, :c, :s, :u, :reason) on conflict (message_id, reporter_id) do nothing""")
			.param("id", reportId)
			.param("m", messageId)
			.param("c", conversationId)
			.param("s", schoolId)
			.param("u", reporterId)
			.param("reason", reason)
			.update();
		if (inserted == 0) {
			return;
		}
		this.audit.record(AuditEntry.of("messaging.message.report", AuditOutcome.SUCCESS)
			.actor(reporterId)
			.target("message", messageId)
			.school(schoolId)
			.metadata("reportId", reportId.toString())
			.metadata("conversationId", conversationId.toString())
			.metadata("reason", reason));
		notifyAboutReport(reportId, access, reporterId);
	}

	private void notifyAboutReport(UUID reportId, Access access, UUID reporterId) {
		UUID schoolId = access.conversation().schoolId();
		Set<UUID> moderators = new LinkedHashSet<>(this.jdbc.sql("""
				select user_id from conversation_members where conversation_id = :c and left_at is null
				  and member_role in ('OWNER', 'MODERATOR')""")
			.param("c", access.conversation().id())
			.query(UUID.class)
			.list());
		moderators.remove(reporterId);
		moderators.removeIf((u) -> this.notifications.alreadyNotified(u, "MESSAGE_REPORTED", "message_report", reportId));
		if (!moderators.isEmpty()) {
			this.notifications.notify(NotificationRequest.to("MESSAGE_REPORTED", moderators)
				.school(schoolId)
				.source("message_report", reportId)
				.path("/messages/" + access.conversation().id())
				.data("conversationId", access.conversation().id().toString())
				.build());
		}
		Set<UUID> reviewers = new LinkedHashSet<>(this.jdbc.sql("""
				select distinct ra.user_id from role_assignments ra
				join role_permissions rp on rp.role_code = ra.role_code and rp.permission_code = :p
				where ra.status = 'ACTIVE' and ra.scope_type = 'SCHOOL' and ra.school_id = :s""")
			.param("p", Permissions.SCHOOL_MESSAGING_REVIEW)
			.param("s", schoolId)
			.query(UUID.class)
			.list());
		reviewers.remove(reporterId);
		reviewers.removeAll(moderators);
		reviewers.removeIf((u) -> this.notifications.alreadyNotified(u, "MESSAGE_REPORTED", "message_report", reportId));
		String slug = this.directory.school(schoolId).map(TenantDirectory.SchoolRef::slug).orElse(null);
		if (!reviewers.isEmpty() && slug != null) {
			this.notifications.notify(NotificationRequest.to("MESSAGE_REPORTED", reviewers)
				.school(schoolId)
				.source("message_report", reportId)
				.path("/s/" + slug + "/messaging/review")
				.build());
		}
	}

}
