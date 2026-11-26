package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.tx.AdvisoryLocks;
import com.nemaz.school.messaging.MessageSent;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.notifications.NotificationRequest;
import com.nemaz.school.notifications.NotificationService;
import com.nemaz.school.realtime.RealtimePublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Delivers a sent message (rule book R9), after the sending transaction committed. Idempotent: the first run claims the message
 * ({@code notified_at}) in the same transaction in which it notifies, so a redelivered event does nothing.
 * <ul>
 * <li><b>Doorbell</b> to every person who may use the conversation right now, the sender's other devices included: ids only.</li>
 * <li><b>Notification</b> (in-app and, by the person's preferences and quiet hours, push; generic text: never the message, the
 * child's name or a full name) to those who may use the conversation, except the sender, people who muted it, people who are
 * reading it this very moment (marked read within the last 30 seconds), people who blocked the sender (their copy hides it) and
 * people who already have an unread message notification of this conversation. A message that reaches a teacher outside office
 * hours is delivered as {@code NEW_MESSAGE_QUIET}: in-app only, no push.</li>
 * </ul>
 */
@Component
class MessageDelivery {

	private static final Logger log = LoggerFactory.getLogger(MessageDelivery.class);

	static final Duration READING_WINDOW = Duration.ofSeconds(30);

	private final JdbcClient jdbc;

	private final Eligibility eligibility;

	private final MessagingPolicy policy;

	private final NotificationService notifications;

	private final RealtimePublisher publisher;

	private final TeacherHours teacherHours;

	private final Clock clock;

	MessageDelivery(JdbcClient jdbc, Eligibility eligibility, MessagingPolicy policy, NotificationService notifications,
			RealtimePublisher publisher, TeacherHours teacherHours, Clock clock) {
		this.jdbc = jdbc;
		this.eligibility = eligibility;
		this.policy = policy;
		this.notifications = notifications;
		this.publisher = publisher;
		this.teacherHours = teacherHours;
		this.clock = clock;
	}

	@ApplicationModuleListener
	void on(MessageSent event) {
		deliver(event);
	}

	/** One delivery (also what tests call to prove idempotency). Returns how many notifications were created. */
	int deliver(MessageSent event) {
		record Msg(Instant sentAt, ActingAs senderActing) {
		}
		int claimed = this.jdbc.sql("""
				update messages set notified_at = :now where id = :m and notified_at is null and seq is not null
				  and status in ('SENT', 'EDITED') and message_type <> 'SYSTEM'""")
			.param("m", event.messageId())
			.param("now", Timestamp.from(this.clock.instant()))
			.update();
		if (claimed == 0) {
			return 0;
		}
		// deliveries of one conversation run one after the other: "one unread notification per conversation" needs it
		AdvisoryLocks.acquire(this.jdbc, "messaging-delivery:" + event.conversationId());
		ConversationRow conv = this.policy.conversation(event.conversationId()).orElse(null);
		Msg msg = this.jdbc.sql("""
				select m.sent_at, cm.acting_as from messages m join conversation_members cm on cm.id = m.sender_member_id where m.id = :m""")
			.param("m", event.messageId())
			.query((rs, i) -> new Msg(Rows.instant(rs, "sent_at"), ActingAs.valueOf(rs.getString("acting_as"))))
			.optional()
			.orElse(null);
		if (conv == null || msg == null) {
			return 0;
		}
		record Recipient(UUID userId, boolean observer, ActingAs acting, Instant mutedUntil, Instant lastReadAt, boolean blockedSender) {
		}
		List<Recipient> recipients = this.eligibility.statement(List.of(conv.schoolId()), """
				select m.user_id, m.member_role, m.acting_as, m.muted_until, m.last_read_at,
				       exists (select 1 from user_blocks b where b.blocker_id = m.user_id and b.blocked_id = :sender) as blocked
				from conversation_members m join conversations c on c.id = m.conversation_id join sp on sp.school_id = c.school_id
				where c.id = :c and m.left_at is null and %s""".formatted(Eligibility.accessible("m")))
			.param("c", conv.id())
			.param("sender", event.senderId())
			.query((rs, i) -> new Recipient(Rows.uuid(rs, "user_id"), "OBSERVER".equals(rs.getString("member_role")),
					ActingAs.valueOf(rs.getString("acting_as")), Rows.instant(rs, "muted_until"), Rows.instant(rs, "last_read_at"),
					rs.getBoolean("blocked")))
			.list();
		Instant now = this.clock.instant();
		boolean silentForTeachers = this.teacherHours.silentForTeachers(conv, msg.senderActing(), msg.sentAt());
		for (Recipient r : recipients) {
			try {
				this.publisher.toUser(r.userId(), RealtimePublisher.MESSAGES_QUEUE,
						new Doorbell.Ring("message.created", conv.id(), event.messageId(), now));
			}
			catch (RuntimeException ex) {
				log.warn("Doorbell of message {} failed: {}", event.messageId(), ex.getClass().getSimpleName());
			}
		}
		List<UUID> loud = new ArrayList<>();
		List<UUID> quiet = new ArrayList<>();
		for (Recipient r : recipients) {
			if (r.userId().equals(event.senderId()) || (r.mutedUntil() != null && r.mutedUntil().isAfter(now))
					|| (r.lastReadAt() != null && r.lastReadAt().isAfter(now.minus(READING_WINDOW)))
					|| (r.blockedSender() && !r.observer())) {
				continue;
			}
			boolean silent = silentForTeachers && r.acting() == ActingAs.TEACHER && !r.observer();
			String type = silent ? "NEW_MESSAGE_QUIET" : "NEW_MESSAGE";
			if (this.notifications.hasUnread(r.userId(), type, "conversation", conv.id())) {
				continue;
			}
			(silent ? quiet : loud).add(r.userId());
		}
		int created = 0;
		created += notify("NEW_MESSAGE", loud, conv);
		created += notify("NEW_MESSAGE_QUIET", quiet, conv);
		return created;
	}

	private int notify(String type, List<UUID> users, ConversationRow conv) {
		if (users.isEmpty()) {
			return 0;
		}
		return this.notifications.notify(NotificationRequest.to(type, users)
			.school(conv.schoolId())
			.source("conversation", conv.id())
			.path("/messages/" + conv.id())
			.data("conversationId", conv.id().toString())
			.build()).size();
	}

}
