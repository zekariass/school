package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.notifications.NotificationService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read state (rule book R10) and mute. The watermark ({@code last_read_seq}) only moves forward and marking read again changes
 * nothing but the "currently reading" time. "Seen" receipts ({@code message_reads}) exist only for DIRECT conversations and only
 * for readers who are participants: an observer's reading never writes one.
 */
@Service
public class ReadState {

	static final Duration MAX_MUTE = Duration.ofDays(365);

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final Clock clock;

	private final NotificationService notifications;

	ReadState(JdbcClient jdbc, MessagingPolicy policy, Clock clock, NotificationService notifications) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.clock = clock;
		this.notifications = notifications;
	}

	/** Reads up to {@code upToMessageId} (the newest message when null). Idempotent; never moves the watermark back. */
	@Transactional
	public void markRead(CurrentUser user, UUID conversationId, UUID upToMessageId) {
		Access access = this.policy.access(user, conversationId);
		Long target;
		if (upToMessageId == null) {
			target = this.jdbc.sql("""
					select max(seq) from messages where conversation_id = :c and seq is not null
					  and status in ('SENT', 'EDITED', 'DELETED', 'HIDDEN')""")
				.param("c", conversationId)
				.query(Long.class)
				.optional()
				.orElse(null);
		}
		else {
			target = this.jdbc.sql("select seq from messages where id = :m and conversation_id = :c and seq is not null")
				.param("m", upToMessageId)
				.param("c", conversationId)
				.query(Long.class)
				.optional()
				.orElseThrow(() -> ApiException.notFound("messaging.message_not_found"));
		}
		Timestamp now = Timestamp.from(this.clock.instant().truncatedTo(ChronoUnit.MICROS));
		long old = access.member().lastReadSeq();
		long to = (target == null) ? old : Math.max(old, target);
		this.jdbc.sql("""
				update conversation_members
				set last_read_seq = :seq,
				    last_read_message_id = case when :seq > last_read_seq then
				        (select id from messages where conversation_id = :c and seq = :seq) else last_read_message_id end,
				    last_read_at = :now
				where id = :m""")
			.param("seq", to)
			.param("c", conversationId)
			.param("now", now)
			.param("m", access.member().id())
			.update();
		// opening the conversation reads its message notifications (so that the next message can ring again)
		this.notifications.markReadForSource(user.userId(), java.util.List.of("NEW_MESSAGE", "NEW_MESSAGE_QUIET"), "conversation",
				conversationId);
		if (access.conversation().direct() && !access.member().observer() && to > old) {
			this.jdbc.sql("""
					insert into message_reads (message_id, user_id, read_at)
					select m.id, :u, :now from messages m
					where m.conversation_id = :c and m.seq > :old and m.seq <= :to and m.sender_id <> :u
					  and m.status in ('SENT', 'EDITED')
					on conflict do nothing""")
				.param("u", user.userId())
				.param("now", now)
				.param("c", conversationId)
				.param("old", old)
				.param("to", to)
				.update();
		}
	}

	/** Silences pushes of the conversation until {@code until} (at most a year); in-app items still arrive. */
	@Transactional
	public Instant mute(CurrentUser user, UUID conversationId, Instant until) {
		Access access = this.policy.access(user, conversationId);
		Instant now = this.clock.instant();
		if (until == null || !until.isAfter(now) || until.isAfter(now.plus(MAX_MUTE))) {
			throw ApiException.badRequest("messaging.mute_invalid");
		}
		this.jdbc.sql("update conversation_members set muted_until = :u where id = :m")
			.param("u", Timestamp.from(until))
			.param("m", access.member().id())
			.update();
		return until;
	}

	@Transactional
	public void unmute(CurrentUser user, UUID conversationId) {
		Access access = this.policy.access(user, conversationId);
		this.jdbc.sql("update conversation_members set muted_until = null where id = :m").param("m", access.member().id()).update();
	}

}
