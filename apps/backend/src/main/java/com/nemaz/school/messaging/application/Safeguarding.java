package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>The safeguarding floor</b> (rule book R5): every DIRECT conversation between an adult and a student has the student's
 * ACTIVE, not custody-restricted guardians as read-only OBSERVER members, or, when there are none, is {@code admin_visible}:
 * reviewers (school.messaging.review) may read it, only through the audited review endpoint. There is no setting for it.
 *
 * <p>
 * {@link #reconcile} is the single, idempotent routine: it adds the guardians who are missing (and notifies them once), ends the
 * observers whose link is no longer valid, and keeps {@code admin_visible} equal to "no observer". It runs when the conversation
 * is created and whenever a guardian link changes. It is a cleanup, not the protection: the live eligibility check
 * ({@link com.nemaz.school.messaging.infrastructure.Eligibility}) already denies a restricted or revoked guardian on the very
 * next request. A guardian who is the other participant (a teacher who is the student's only guardian) is never added: nobody
 * observes their own conversation, so it is admin-visible.
 */
@Component
public class Safeguarding {

	private final JdbcClient jdbc;

	private final NotificationService notifications;

	private final AuditLog audit;

	private final Clock clock;

	Safeguarding(JdbcClient jdbc, NotificationService notifications, AuditLog audit, Clock clock) {
		this.jdbc = jdbc;
		this.notifications = notifications;
		this.audit = audit;
		this.clock = clock;
	}

	/** Brings the observers and the admin-visible flag of one safeguarded conversation in line with the guardian links. */
	@Transactional
	public void reconcile(UUID conversationId) {
		record Conv(UUID studentId, UUID schoolId) {
		}
		Conv conv = this.jdbc.sql("""
				select context_student_id, school_id from conversations
				where id = :c and safeguarded and conversation_type = 'DIRECT'""")
			.param("c", conversationId)
			.query((rs, i) -> new Conv(rs.getObject("context_student_id", UUID.class), rs.getObject("school_id", UUID.class)))
			.optional()
			.orElse(null);
		if (conv == null) {
			return;
		}
		Set<UUID> participants = new LinkedHashSet<>(this.jdbc.sql("""
				select user_id from conversation_members where conversation_id = :c and left_at is null and member_role <> 'OBSERVER'""")
			.param("c", conversationId)
			.query(UUID.class)
			.list());
		Set<UUID> desired = new LinkedHashSet<>(this.jdbc.sql("""
				select guardian_id from student_guardians
				where student_id = :s and status = 'ACTIVE' and not custody_restricted
				order by is_primary desc, created_at, guardian_id""")
			.param("s", conv.studentId())
			.query(UUID.class)
			.list());
		desired.removeAll(participants);
		record Observer(UUID id, UUID userId) {
		}
		List<Observer> current = this.jdbc.sql("""
				select id, user_id from conversation_members where conversation_id = :c and member_role = 'OBSERVER' and left_at is null""")
			.param("c", conversationId)
			.query((rs, i) -> new Observer(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)))
			.list();
		Set<UUID> have = new LinkedHashSet<>();
		current.forEach((o) -> have.add(o.userId()));
		var now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		List<UUID> added = new ArrayList<>();
		for (UUID guardian : desired) {
			if (have.contains(guardian)) {
				continue;
			}
			int inserted = this.jdbc.sql("""
					insert into conversation_members (id, conversation_id, school_id, user_id, member_role, acting_as,
					                                  on_behalf_of_student_id, joined_at)
					values (:id, :c, :s, :u, 'OBSERVER', 'GUARDIAN', :st, :now)
					on conflict (conversation_id, user_id) where left_at is null do nothing""")
				.param("id", Ids.newId())
				.param("c", conversationId)
				.param("s", conv.schoolId())
				.param("u", guardian)
				.param("st", conv.studentId())
				.param("now", now)
				.update();
			if (inserted > 0) {
				added.add(guardian);
			}
		}
		int ended = 0;
		for (Observer observer : current) {
			if (!desired.contains(observer.userId())) {
				ended += this.jdbc.sql("""
						update conversation_members set left_at = :now, left_reason = 'LINK_CHANGED'
						where id = :id and left_at is null""")
					.param("now", now)
					.param("id", observer.id())
					.update();
			}
		}
		boolean adminVisible = desired.isEmpty();
		int flagged = this.jdbc.sql("update conversations set admin_visible = :v, updated_at = :now where id = :c and admin_visible <> :v")
			.param("v", adminVisible)
			.param("now", now)
			.param("c", conversationId)
			.update();
		for (UUID guardian : added) {
			notifyObserver(conv.schoolId(), conversationId, guardian);
		}
		if (!added.isEmpty() || ended > 0 || flagged > 0) {
			this.audit.record(AuditEntry.of("messaging.safeguarding.reconcile", AuditOutcome.SUCCESS)
				.target("conversation", conversationId)
				.school(conv.schoolId())
				.metadata("observersAdded", added.size())
				.metadata("observersEnded", ended)
				.metadata("adminVisible", adminVisible));
		}
	}

	/** Reconciles every safeguarded conversation about the student (a guardian link of theirs changed). */
	@Transactional
	public int reconcileStudent(UUID studentId) {
		List<UUID> ids = this.jdbc.sql("""
				select id from conversations where context_student_id = :s and safeguarded and status in ('ACTIVE', 'LOCKED', 'ARCHIVED')
				order by id""")
			.param("s", studentId)
			.query(UUID.class)
			.list();
		ids.forEach(this::reconcile);
		return ids.size();
	}

	private void notifyObserver(UUID schoolId, UUID conversationId, UUID guardian) {
		if (this.notifications.alreadyNotified(guardian, "MESSAGE_OBSERVER_ADDED", "conversation", conversationId)) {
			return;
		}
		this.notifications.notify(NotificationRequest.to("MESSAGE_OBSERVER_ADDED", guardian)
			.school(schoolId)
			.source("conversation", conversationId)
			.path("/messages/" + conversationId)
			.data("conversationId", conversationId.toString())
			.build());
	}

}
