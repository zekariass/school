package com.nemaz.school.messaging.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.tx.AfterCommit;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.realtime.RealtimePublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The real-time "doorbell" (rule book R9): tells the open apps of the people who may use a conversation <em>right now</em> that
 * something changed, on {@code /user/queue/messages}. The payload carries ids only (no text, no names): the client fetches the
 * content through the API, which applies every rule again, so a socket that outlives a person's access learns nothing but that a
 * conversation they cannot open rang. Best effort: everything is stored anyway.
 */
@Component
public class Doorbell {

	private static final Logger log = LoggerFactory.getLogger(Doorbell.class);

	/** {@code type}: {@code message.created} or {@code message.updated}; {@code messageId} may be null. */
	public record Ring(String type, UUID conversationId, UUID messageId, Instant at) {
	}

	private final Eligibility eligibility;

	private final RealtimePublisher publisher;

	private final Clock clock;

	Doorbell(Eligibility eligibility, RealtimePublisher publisher, Clock clock) {
		this.eligibility = eligibility;
		this.publisher = publisher;
		this.clock = clock;
	}

	/** Rings after the current transaction commits. */
	public void ringAfterCommit(UUID schoolId, UUID conversationId, UUID messageId, String type) {
		AfterCommit.run(() -> ring(schoolId, conversationId, messageId, type));
	}

	public void ring(UUID schoolId, UUID conversationId, UUID messageId, String type) {
		try {
			List<UUID> users = this.eligibility.statement(List.of(schoolId), """
					select m.user_id from conversation_members m
					join conversations c on c.id = m.conversation_id
					join sp on sp.school_id = c.school_id
					where c.id = :c and m.left_at is null and %s""".formatted(Eligibility.accessible("m")))
				.param("c", conversationId)
				.query(UUID.class)
				.list();
			Ring ring = new Ring(type, conversationId, messageId, this.clock.instant());
			for (UUID user : users) {
				this.publisher.toUser(user, RealtimePublisher.MESSAGES_QUEUE, ring);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Doorbell of conversation {} failed: {}", conversationId, ex.getClass().getSimpleName());
		}
	}

}
