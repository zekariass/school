package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduled-message job (rule book R8.5). Every minute (ShedLock {@code messaging.send-scheduled}, returns {@code Integer}
 * because ShedLock refuses primitives) it releases the messages whose time has come, each in its own transaction with
 * {@code FOR UPDATE SKIP LOCKED}, so two instances or two runs release a message once. <b>At release time the rules are checked
 * again</b> as for any send (R1: the sender must still be able to use the conversation; R5: the safeguarding floor is part of
 * that check; the conversation must be open and the sender allowed to post): a message that may no longer be sent is marked
 * BLOCKED and its sender is told ({@code MESSAGE_SCHEDULE_BLOCKED}), never sent. Quiet hours are those of the
 * <em>recipients</em>, applied by the notification pipeline to the push when the message is delivered (the in-app message arrives
 * at once).
 */
@Component
public class MessagingMaintenance {

	private static final int BATCH = 50;

	private static final Logger log = LoggerFactory.getLogger(MessagingMaintenance.class);

	private final Releaser releaser;

	MessagingMaintenance(Releaser releaser) {
		this.releaser = releaser;
	}

	@Scheduled(cron = "25 * * * * *", zone = "UTC")
	@SchedulerLock(name = "messaging.send-scheduled", lockAtMostFor = "PT5M")
	public Integer sendScheduledJob() {
		return sendScheduled();
	}

	/** One pass of the job (also what tests call); returns how many messages were sent. */
	public Integer sendScheduled() {
		int sent = 0;
		for (UUID id : this.releaser.dueIds(BATCH)) {
			try {
				if (this.releaser.release(id)) {
					sent++;
				}
			}
			catch (RuntimeException ex) {
				// left SCHEDULED: the next run tries again; one bad message never blocks the others
				log.warn("Scheduled message {} could not be released: {}", id, ex.getClass().getSimpleName());
			}
		}
		return sent;
	}

	/** Transactional steps of the job (a separate bean so that each release has its own transaction). */
	@Component
	public static class Releaser {

		private final JdbcClient jdbc;

		private final MessagingPolicy policy;

		private final MessageService messages;

		private final NotificationService notifications;

		private final AuditLog audit;

		private final Clock clock;

		Releaser(JdbcClient jdbc, MessagingPolicy policy, MessageService messages, NotificationService notifications,
				AuditLog audit, Clock clock) {
			this.jdbc = jdbc;
			this.policy = policy;
			this.messages = messages;
			this.notifications = notifications;
			this.audit = audit;
			this.clock = clock;
		}

		public List<UUID> dueIds(int limit) {
			return this.jdbc.sql("select id from messages where status = 'SCHEDULED' and send_at <= :now order by send_at, id limit :n")
				.param("now", Timestamp.from(this.clock.instant()))
				.param("n", limit)
				.query(UUID.class)
				.list();
		}

		@Transactional
		public boolean release(UUID messageId) {
			record Due(UUID conversationId, UUID senderId) {
			}
			Optional<Due> due = this.jdbc.sql("""
					select conversation_id, sender_id from messages
					where id = :m and status = 'SCHEDULED' and send_at <= :now for update skip locked""")
				.param("m", messageId)
				.param("now", Timestamp.from(this.clock.instant()))
				.query((rs, i) -> new Due(rs.getObject("conversation_id", UUID.class), rs.getObject("sender_id", UUID.class)))
				.optional();
			if (due.isEmpty()) {
				return false;
			}
			Optional<Access> access = this.policy.find(due.get().senderId(), due.get().conversationId(), false);
			String refusal = null;
			if (access.isEmpty()) {
				refusal = "not_allowed";
			}
			else {
				ApiException why = this.policy.postRefusal(access.get());
				if (why != null) {
					refusal = why.code();
				}
				else if (access.get().member().actingAs() == com.nemaz.school.messaging.domain.ActingAs.STUDENT) {
					refusal = "student";
				}
			}
			var now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
			if (refusal != null) {
				this.jdbc.sql("update messages set status = 'BLOCKED', blocked_code = :code where id = :m")
					.param("code", refusal)
					.param("m", messageId)
					.update();
				UUID school = this.jdbc.sql("select school_id from messages where id = :m")
					.param("m", messageId)
					.query(UUID.class)
					.single();
				this.notifications.notify(NotificationRequest.to("MESSAGE_SCHEDULE_BLOCKED", due.get().senderId())
					.school(school)
					.source("message", messageId)
					.path("/messages/" + due.get().conversationId())
					.data("conversationId", due.get().conversationId().toString())
					.build());
				this.audit.record(AuditEntry.of("messaging.message.schedule_blocked", AuditOutcome.DENIED)
					.target("message", messageId)
					.school(school)
					.actor(due.get().senderId())
					.metadata("conversationId", due.get().conversationId().toString())
					.metadata("code", refusal));
				return false;
			}
			this.jdbc.sql("update messages set status = 'SENT', seq = nextval('message_seq'), sent_at = :now where id = :m")
				.param("now", Timestamp.from(now))
				.param("m", messageId)
				.update();
			this.messages.announce(messageId, access.get(), now);
			return true;
		}

	}

}
