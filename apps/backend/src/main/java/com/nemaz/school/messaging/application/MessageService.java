package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.audit.AuditEntry;
import com.nemaz.school.audit.AuditLog;
import com.nemaz.school.audit.AuditOutcome;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.id.Ids;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaService;
import com.nemaz.school.messaging.MessageSent;
import com.nemaz.school.messaging.application.MessagingModels.MessageItemView;
import com.nemaz.school.messaging.application.MessagingModels.SendResultView;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.MessageText;
import com.nemaz.school.messaging.infrastructure.MessagingRows.MemberRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;
import com.nemaz.school.system.RateLimiter;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sending, editing, deleting and reacting (rule book R8). Every method starts with {@link MessagingPolicy#access} (the live
 * eligibility of R1) and, for writes, {@link MessagingPolicy#requirePostable}; nothing is trusted from the stored membership.
 *
 * <p>
 * <b>Idempotent send:</b> {@code client_message_id} is unique per sender. A repeat is checked against access first (someone who
 * lost access gets the refusal, not the old message), then answered with the original and {@code created: false}; two requests
 * racing on the same id store one row ({@code on conflict do nothing}) and both get it. Repeats do not consume rate limit.
 */
@Service
public class MessageService {

	public static final int MAX_MEDIA = 4;

	static final int ADULT_PER_MINUTE = 30;

	static final int ADULT_PER_HOUR = 600;

	static final int STUDENT_PER_MINUTE = 10;

	static final int STUDENT_PER_HOUR = 200;

	static final Duration MIN_SCHEDULE_AHEAD = Duration.ofMinutes(1);

	static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(90);

	/** The reactions offered (a fixed set: free emoji would need content moderation). */
	public static final List<String> REACTIONS = List.of("👍", "❤️", "😂", "😮",
			"😢", "🙏", "👏", "🎉", "✅", "❓", "👀", "💯");

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final MessageQueries queries;

	private final MediaService media;

	private final RateLimiter limiter;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	private final MessagingSettings settings;

	private final TeacherHours teacherHours;

	private final Doorbell doorbell;

	MessageService(JdbcClient jdbc, MessagingPolicy policy, MessageQueries queries, MediaService media, RateLimiter limiter,
			AuditLog audit, ApplicationEventPublisher events, Clock clock, MessagingSettings settings,
			TeacherHours teacherHours, Doorbell doorbell) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.queries = queries;
		this.media = media;
		this.limiter = limiter;
		this.audit = audit;
		this.events = events;
		this.clock = clock;
		this.settings = settings;
		this.teacherHours = teacherHours;
		this.doorbell = doorbell;
	}

	/** What a client sends. {@code messageType} defaults to MEDIA when there are images, else TEXT. */
	public record SendCommand(UUID clientMessageId, String messageType, String body, String bodyLanguageCode,
			List<UUID> mediaIds, UUID replyToMessageId, Instant sendAt) {

		public SendCommand {
			mediaIds = (mediaIds == null) ? List.of() : List.copyOf(new LinkedHashSet<>(mediaIds));
		}

		@Override
		public String toString() {
			return "SendCommand[redacted]";
		}

	}

	// ---------------------------------------------------------------------------------------------------------------
	// send

	@Transactional
	public SendResultView send(CurrentUser user, UUID conversationId, SendCommand cmd) {
		Access access = this.policy.access(user, conversationId);
		this.policy.requirePostable(access);
		if (cmd.clientMessageId() == null) {
			throw ApiException.badRequest("messaging.client_message_id_required");
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		MemberRow member = access.member();
		UUID schoolId = access.conversation().schoolId();

		Existing existing = existing(user.userId(), cmd.clientMessageId());
		if (existing != null) {
			if (!existing.conversationId().equals(conversationId)) {
				throw ApiException.conflict("messaging.client_message_id_reused");
			}
			return new SendResultView(this.queries.view(user, access, existing.id()), false,
					afterHours(access, existing.sentAt()));
		}

		String type = (cmd.messageType() == null) ? (cmd.mediaIds().isEmpty() ? "TEXT" : "MEDIA") : cmd.messageType();
		if ("VOICE".equals(type) || "SYSTEM".equals(type) || !("TEXT".equals(type) || "MEDIA".equals(type))) {
			throw ApiException.badRequest("messaging.type_not_supported");
		}
		String body = (cmd.body() == null) ? "" : MessageText.clean(cmd.body());
		if ("TEXT".equals(type)) {
			if (!cmd.mediaIds().isEmpty()) {
				throw ApiException.badRequest("messaging.type_not_supported");
			}
			if (body.isEmpty()) {
				throw ApiException.badRequest("messaging.message_empty");
			}
		}
		else if (cmd.mediaIds().isEmpty() || cmd.mediaIds().size() > MAX_MEDIA) {
			throw ApiException.badRequest(cmd.mediaIds().isEmpty() ? "messaging.message_empty" : "messaging.too_many_media",
					MAX_MEDIA);
		}
		if (MessageText.length(body) > MessageText.MAX_LENGTH) {
			throw ApiException.badRequest("messaging.message_too_long", MessageText.MAX_LENGTH);
		}
		String language = blankToNull(cmd.bodyLanguageCode());
		if (language != null && !this.jdbc.sql("select exists (select 1 from languages where code = :c)")
			.param("c", language)
			.query(Boolean.class)
			.single()) {
			throw ApiException.badRequest("messaging.language_invalid");
		}
		boolean student = member.actingAs() == ActingAs.STUDENT;
		if (!cmd.mediaIds().isEmpty() && student && !this.settings.studentMedia(schoolId)) {
			throw ApiException.forbidden("messaging.student_media_disabled");
		}
		boolean scheduled = cmd.sendAt() != null;
		if (scheduled) {
			if (student) {
				throw ApiException.forbidden("messaging.scheduling_not_allowed");
			}
			if (cmd.sendAt().isBefore(now.plus(MIN_SCHEDULE_AHEAD)) || cmd.sendAt().isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
				throw ApiException.badRequest("messaging.schedule_invalid");
			}
		}
		if (cmd.replyToMessageId() != null && !messageInConversation(cmd.replyToMessageId(), conversationId)) {
			throw ApiException.badRequest("messaging.reply_invalid");
		}
		rateLimit(user.userId(), student);
		for (UUID mediaId : cmd.mediaIds()) {
			this.media.requireAttachable(mediaId, MediaPurpose.MESSAGE, new MediaContext(ContextType.CONVERSATION, conversationId),
					user);
			boolean used = this.jdbc.sql("select exists (select 1 from message_attachments where media_id = :m)")
				.param("m", mediaId)
				.query(Boolean.class)
				.single();
			if (used) {
				throw ApiException.conflict("messaging.media_already_attached");
			}
		}

		UUID id = Ids.newId();
		int inserted = this.jdbc.sql("""
				insert into messages (id, conversation_id, school_id, sender_id, sender_member_id, message_type, body,
				                      body_language_code, reply_to_message_id, client_message_id, send_at, status, seq, sent_at, created_at)
				values (:id, :c, :s, :u, :m, :type, :body, :lang, :reply, :client, cast(:send_at as timestamptz), :status,
				        case when :scheduled then null else nextval('message_seq') end,
				        case when :scheduled then null else cast(:now as timestamptz) end, :now)
				on conflict (sender_id, client_message_id) do nothing""")
			.param("id", id)
			.param("c", conversationId)
			.param("s", schoolId)
			.param("u", user.userId())
			.param("m", member.id())
			.param("type", type)
			.param("body", body.isEmpty() ? null : body)
			.param("lang", language)
			.param("reply", cmd.replyToMessageId())
			.param("client", cmd.clientMessageId())
			.param("send_at", scheduled ? Timestamp.from(cmd.sendAt()) : null)
			.param("status", scheduled ? "SCHEDULED" : "SENT")
			.param("scheduled", scheduled)
			.param("now", Timestamp.from(now))
			.update();
		if (inserted == 0) {
			// a concurrent request with the same client id won: answer with its message
			Existing winner = existing(user.userId(), cmd.clientMessageId());
			if (winner == null || !winner.conversationId().equals(conversationId)) {
				throw ApiException.conflict("messaging.client_message_id_reused");
			}
			return new SendResultView(this.queries.view(user, access, winner.id()), false, afterHours(access, winner.sentAt()));
		}
		int order = 0;
		for (UUID mediaId : cmd.mediaIds()) {
			this.jdbc.sql("insert into message_attachments (message_id, media_id, sort_order) values (:m, :media, :o)")
				.param("m", id)
				.param("media", mediaId)
				.param("o", order++)
				.update();
		}
		if (!scheduled) {
			announce(id, access, now);
		}
		return new SendResultView(this.queries.view(user, access, id), true, !scheduled && afterHours(access, now));
	}

	/** Publishes the message to its conversation: activity time and the event that delivery listens to. */
	void announce(UUID messageId, Access access, Instant at) {
		this.jdbc.sql("update conversations set last_message_at = :t, activity_at = :t where id = :c")
			.param("t", Timestamp.from(at))
			.param("c", access.conversation().id())
			.update();
		this.events.publishEvent(new MessageSent(messageId, access.conversation().id(), access.conversation().schoolId(),
				access.member().userId()));
	}

	private boolean afterHours(Access access, Instant sentAt) {
		return sentAt != null && this.teacherHours.silentForTeachers(access.conversation(), access.member().actingAs(), sentAt);
	}

	private record Existing(UUID id, UUID conversationId, Instant sentAt) {
	}

	private Existing existing(UUID senderId, UUID clientMessageId) {
		return this.jdbc.sql("select id, conversation_id, sent_at from messages where sender_id = :u and client_message_id = :c")
			.param("u", senderId)
			.param("c", clientMessageId)
			.query((rs, i) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
					(rs.getTimestamp("sent_at") != null) ? rs.getTimestamp("sent_at").toInstant() : null))
			.optional()
			.orElse(null);
	}

	private boolean messageInConversation(UUID messageId, UUID conversationId) {
		return this.jdbc.sql("select exists (select 1 from messages where id = :m and conversation_id = :c)")
			.param("m", messageId)
			.param("c", conversationId)
			.query(Boolean.class)
			.single();
	}

	private void rateLimit(UUID userId, boolean student) {
		String key = userId.toString();
		this.limiter.consume("messaging-send-minute", key, student ? STUDENT_PER_MINUTE : ADULT_PER_MINUTE, Duration.ofMinutes(1),
				"messaging.rate_limited");
		this.limiter.consume("messaging-send-hour", key, student ? STUDENT_PER_HOUR : ADULT_PER_HOUR, Duration.ofHours(1),
				"messaging.rate_limited");
	}

	// ---------------------------------------------------------------------------------------------------------------
	// edit, delete

	/** Author only, text messages, within {@code messaging.edit_window_minutes}; the earlier text goes to the history. */
	@Transactional
	public MessageItemView edit(CurrentUser user, UUID conversationId, UUID messageId, String newBody) {
		Access access = this.policy.access(user, conversationId);
		this.policy.requirePostable(access);
		Row row = lock(messageId, conversationId);
		if (!row.senderId().equals(user.userId())) {
			throw ApiException.forbidden("messaging.not_your_message");
		}
		if (!("SENT".equals(row.status()) || "EDITED".equals(row.status())) || !"TEXT".equals(row.type())) {
			throw ApiException.conflict("messaging.message_unavailable");
		}
		int window = this.settings.editWindowMinutes(access.conversation().schoolId());
		if (window <= 0) {
			throw ApiException.conflict("messaging.edit_disabled");
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (now.isAfter(row.sentAt().plus(Duration.ofMinutes(window)))) {
			throw ApiException.conflict("messaging.edit_window_expired", window);
		}
		String body = MessageText.clean(newBody);
		if (body.isEmpty()) {
			throw ApiException.badRequest("messaging.message_empty");
		}
		if (MessageText.length(body) > MessageText.MAX_LENGTH) {
			throw ApiException.badRequest("messaging.message_too_long", MessageText.MAX_LENGTH);
		}
		this.jdbc.sql("insert into message_edits (id, message_id, kind, body, edited_by, edited_at) values (:id, :m, 'EDIT', :b, :u, :t)")
			.param("id", Ids.newId())
			.param("m", messageId)
			.param("b", row.body())
			.param("u", user.userId())
			.param("t", Timestamp.from(now))
			.update();
		this.jdbc.sql("update messages set body = :b, status = 'EDITED', edited_at = :t where id = :m")
			.param("b", body)
			.param("t", Timestamp.from(now))
			.param("m", messageId)
			.update();
		this.audit.record(AuditEntry.of("messaging.message.edit", AuditOutcome.SUCCESS)
			.target("message", messageId)
			.school(access.conversation().schoolId())
			.metadata("conversationId", conversationId.toString()));
		this.doorbell.ringAfterCommit(access.conversation().schoolId(), conversationId, messageId, "message.updated");
		return this.queries.view(user, access, messageId);
	}

	/**
	 * The author deletes their message: members see a tombstone; the original text moves to the history (readable only through
	 * the audited review endpoint) and its images are discarded. A scheduled message that was not sent yet is withdrawn.
	 */
	@Transactional
	public void delete(CurrentUser user, UUID conversationId, UUID messageId) {
		Access access = this.policy.access(user, conversationId);
		if (access.member().observer()) {
			throw ApiException.forbidden("messaging.observer_read_only");
		}
		Row row = lock(messageId, conversationId);
		if (!row.senderId().equals(user.userId())) {
			throw ApiException.forbidden("messaging.not_your_message");
		}
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		if ("SCHEDULED".equals(row.status())) {
			this.jdbc.sql("update messages set status = 'CANCELLED' where id = :m").param("m", messageId).update();
			discardAttachments(messageId);
			return;
		}
		if ("DELETED".equals(row.status())) {
			return;
		}
		if (!("SENT".equals(row.status()) || "EDITED".equals(row.status()) || "HIDDEN".equals(row.status()))) {
			throw ApiException.conflict("messaging.message_unavailable");
		}
		if (row.body() != null) {
			this.jdbc.sql("insert into message_edits (id, message_id, kind, body, edited_by, edited_at) values (:id, :m, 'DELETE', :b, :u, :t)")
				.param("id", Ids.newId())
				.param("m", messageId)
				.param("b", row.body())
				.param("u", user.userId())
				.param("t", Timestamp.from(now))
				.update();
		}
		this.jdbc.sql("update messages set body = null, status = 'DELETED', deleted_at = :t where id = :m")
			.param("t", Timestamp.from(now))
			.param("m", messageId)
			.update();
		discardAttachments(messageId);
		this.audit.record(AuditEntry.of("messaging.message.delete", AuditOutcome.SUCCESS)
			.target("message", messageId)
			.school(access.conversation().schoolId())
			.metadata("conversationId", conversationId.toString()));
		this.doorbell.ringAfterCommit(access.conversation().schoolId(), conversationId, messageId, "message.updated");
	}

	private void discardAttachments(UUID messageId) {
		List<UUID> mediaIds = this.jdbc.sql("select media_id from message_attachments where message_id = :m")
			.param("m", messageId)
			.query(UUID.class)
			.list();
		this.jdbc.sql("delete from message_attachments where message_id = :m").param("m", messageId).update();
		mediaIds.forEach(this.media::discard);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// reactions

	@Transactional
	public MessageItemView react(CurrentUser user, UUID conversationId, UUID messageId, String emoji) {
		Access access = this.policy.access(user, conversationId);
		if (access.member().observer()) {
			throw ApiException.forbidden("messaging.observer_read_only");
		}
		if (!access.conversation().active()) {
			throw ApiException.conflict("messaging.conversation_closed");
		}
		if (emoji == null || !REACTIONS.contains(emoji)) {
			throw ApiException.badRequest("messaging.reaction_invalid");
		}
		Row row = lock(messageId, conversationId);
		if (!("SENT".equals(row.status()) || "EDITED".equals(row.status()))) {
			throw ApiException.conflict("messaging.message_unavailable");
		}
		this.queries.view(user, access, messageId);
		this.jdbc.sql("insert into message_reactions (message_id, user_id, emoji) values (:m, :u, :e) on conflict do nothing")
			.param("m", messageId)
			.param("u", user.userId())
			.param("e", emoji)
			.update();
		this.doorbell.ringAfterCommit(access.conversation().schoolId(), conversationId, messageId, "message.updated");
		return this.queries.view(user, access, messageId);
	}

	@Transactional
	public MessageItemView unreact(CurrentUser user, UUID conversationId, UUID messageId, String emoji) {
		Access access = this.policy.access(user, conversationId);
		if (access.member().observer()) {
			throw ApiException.forbidden("messaging.observer_read_only");
		}
		this.queries.view(user, access, messageId);
		this.jdbc.sql("delete from message_reactions where message_id = :m and user_id = :u and emoji = :e")
			.param("m", messageId)
			.param("u", user.userId())
			.param("e", emoji)
			.update();
		this.doorbell.ringAfterCommit(access.conversation().schoolId(), conversationId, messageId, "message.updated");
		return this.queries.view(user, access, messageId);
	}

	// ---------------------------------------------------------------------------------------------------------------

	record Row(UUID id, UUID senderId, String status, String type, String body, Instant sentAt) {
	}

	/** The message of the conversation, locked for the transaction; 404 when it is not there. */
	Row lock(UUID messageId, UUID conversationId) {
		Optional<Row> row = this.jdbc.sql("""
				select id, sender_id, status, message_type, body, sent_at from messages
				where id = :m and conversation_id = :c for update""")
			.param("m", messageId)
			.param("c", conversationId)
			.query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("sender_id", UUID.class),
					rs.getString("status"), rs.getString("message_type"), rs.getString("body"),
					(rs.getTimestamp("sent_at") != null) ? rs.getTimestamp("sent_at").toInstant() : null))
			.optional();
		return row.orElseThrow(() -> ApiException.notFound("messaging.message_not_found"));
	}

	static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	/** Used by the scheduler: the ids of attached media of a message. */
	Set<UUID> attachmentIds(UUID messageId) {
		return new LinkedHashSet<>(this.jdbc.sql("select media_id from message_attachments where message_id = :m")
			.param("m", messageId)
			.query(UUID.class)
			.list());
	}

}
