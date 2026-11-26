package com.nemaz.school.messaging.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.moderation.ModerationTarget;
import com.nemaz.school.moderation.ReportTarget;
import com.nemaz.school.moderation.ReviewContent;
import com.nemaz.school.moderation.TargetRef;
import com.nemaz.school.moderation.TargetTypes;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The messaging side of the moderation SPIs (docs/Safeguarding_Rules.md S1.1, S2.4, S3).
 * <ul>
 * <li><b>Message:</b> reportable by any member (observers too) who may see it now, through the policy engine and the message
 * query that every read uses (so a restricted guardian, a former member or somebody who blocked the sender cannot report it).
 * A report through the generic endpoint also writes the messaging record ({@code message_reports}), which makes the
 * conversation reviewable through the audited review path. A message of a safeguarded (adult-student) conversation is flagged
 * so the case starts HIGH.</li>
 * <li><b>Person:</b> reportable when you share an active conversation with them (the class, school and guardian contexts are
 * the people module's).</li>
 * <li><b>Hide and remove</b> use the existing HIDDEN status of messages; the original text, the edit history and the
 * attachments are kept and stay readable through the audited review. The message is never read through the moderation module:
 * the case-bound read is a pointer to the audited, reason-bound review of its conversation.</li>
 * </ul>
 */
@Component
class MessagingModeration implements ReportTarget, ModerationTarget {

	private static final Set<String> TYPES = Set.of(TargetTypes.MESSAGE, TargetTypes.USER);

	private static final Set<String> MODERATION_TYPES = Set.of(TargetTypes.MESSAGE);

	private static final String HIDE_REASON = "Hidden by a moderation case";

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final MessageQueries queries;

	private final ModerationService moderation;

	private final Doorbell doorbell;

	private final Clock clock;

	MessagingModeration(JdbcClient jdbc, MessagingPolicy policy, MessageQueries queries, ModerationService moderation,
			Doorbell doorbell, Clock clock) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.queries = queries;
		this.moderation = moderation;
		this.doorbell = doorbell;
		this.clock = clock;
	}

	@Override
	public Set<String> targetTypes() {
		return TYPES;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// ReportTarget

	private record MessageFacts(UUID conversationId, UUID senderId) {
	}

	private Optional<MessageFacts> facts(UUID messageId) {
		return this.jdbc.sql("select conversation_id, sender_id from messages where id = :m and seq is not null")
			.param("m", messageId)
			.query((rs, i) -> new MessageFacts(rs.getObject("conversation_id", UUID.class), rs.getObject("sender_id", UUID.class)))
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TargetRef> resolveFor(CurrentUser reporter, String type, UUID id) {
		if (TargetTypes.USER.equals(type)) {
			return sharedConversation(reporter.userId(), id);
		}
		MessageFacts facts = facts(id).orElse(null);
		if (facts == null) {
			return Optional.empty();
		}
		Access access = this.policy.find(reporter.userId(), facts.conversationId(), false).orElse(null);
		if (access == null) {
			return Optional.empty();
		}
		try {
			var seen = this.queries.view(reporter, access, id);
			if (!"SENT".equals(seen.status()) && !"EDITED".equals(seen.status())) {
				return Optional.empty();
			}
		}
		catch (ApiException ex) {
			return Optional.empty();
		}
		return Optional.of(ref(access.conversation(), id, facts.senderId()));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TargetRef> describe(String type, UUID id) {
		if (!TargetTypes.MESSAGE.equals(type)) {
			return Optional.empty();
		}
		return facts(id).flatMap((f) -> this.policy.conversation(f.conversationId()).map((c) -> ref(c, id, f.senderId())));
	}

	private static TargetRef ref(ConversationRow conversation, UUID messageId, UUID senderId) {
		return new TargetRef(TargetTypes.MESSAGE, messageId, conversation.schoolId(), senderId, conversation.safeguarded(),
				conversation.id());
	}

	/** Two people with an active membership in one conversation of a school: the "shared conversation" context of a person. */
	private Optional<TargetRef> sharedConversation(UUID reporterId, UUID otherId) {
		if (reporterId.equals(otherId)) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				select c.school_id from conversation_members a
				join conversation_members b on b.conversation_id = a.conversation_id and b.left_at is null and b.user_id = :other
				join conversations c on c.id = a.conversation_id
				where a.user_id = :me and a.left_at is null and c.status = 'ACTIVE' limit 1""")
			.param("me", reporterId)
			.param("other", otherId)
			.query(UUID.class)
			.optional()
			.map((school) -> new TargetRef(TargetTypes.USER, otherId, school, otherId));
	}

	@Override
	@Transactional
	public void onReportAccepted(UUID reportId, UUID reporterId, TargetRef target, String reasonCode, String details) {
		if (TargetTypes.MESSAGE.equals(target.type())) {
			this.moderation.recordExternalReport(reportId, reporterId, target.id(), messagingReason(reasonCode));
		}
	}

	static String messagingReason(String code) {
		return switch (code) {
			case "HARASSMENT" -> "BULLYING";
			case "SAFETY_CONCERN" -> "SAFETY";
			case "INAPPROPRIATE", "PRIVACY", "SPAM" -> code;
			default -> "OTHER";
		};
	}

	static String moderationReason(String messagingReason) {
		return switch (messagingReason) {
			case "BULLYING" -> "HARASSMENT";
			case "SAFETY" -> "SAFETY_CONCERN";
			case "INAPPROPRIATE", "PRIVACY", "SPAM" -> messagingReason;
			default -> "OTHER";
		};
	}

	// ---------------------------------------------------------------------------------------------------------------
	// ModerationTarget (messages only)

	@Override
	@Transactional
	public void hold(String type, UUID id, UUID caseId) {
		this.jdbc.sql("update messages set moderation_case_id = :c where id = :id and moderation_case_id is null")
			.param("c", caseId)
			.param("id", id)
			.update();
	}

	@Override
	@Transactional
	public void takeDown(String type, UUID id, UUID caseId, UUID actorId, boolean removal) {
		Timestamp now = Timestamp.from(this.clock.instant());
		int flipped = this.jdbc.sql("""
				update messages set status = 'HIDDEN', hidden_at = :now, hidden_by = :by, hide_reason = :reason,
				       moderation_case_id = :c, moderation_hidden_at = :now
				where id = :id and status in ('SENT', 'EDITED')""")
			.param("now", now)
			.param("by", actorId)
			.param("reason", HIDE_REASON)
			.param("c", caseId)
			.param("id", id)
			.update();
		if (removal && flipped == 0) {
			this.jdbc.sql("update messages set moderation_case_id = :c, moderation_hidden_at = coalesce(moderation_hidden_at, :now) where id = :id")
				.param("c", caseId)
				.param("now", now)
				.param("id", id)
				.update();
		}
		ring(id);
	}

	@Override
	@Transactional
	public void restore(String type, UUID id, UUID caseId, UUID actorId) {
		this.jdbc.sql("""
				update messages set status = case when edited_at is null then 'SENT' else 'EDITED' end, hidden_at = null,
				       hidden_by = null, hide_reason = null, moderation_hidden_at = null
				where id = :id and status = 'HIDDEN' and moderation_case_id = :c and moderation_hidden_at is not null""")
			.param("c", caseId)
			.param("id", id)
			.update();
		ring(id);
	}

	private void ring(UUID messageId) {
		this.jdbc.sql("select school_id, conversation_id from messages where id = :m")
			.param("m", messageId)
			.query((rs, i) -> {
				this.doorbell.ringAfterCommit(rs.getObject("school_id", UUID.class), rs.getObject("conversation_id", UUID.class), messageId,
						"message.updated");
				return 0;
			})
			.optional();
	}

	@Override
	@Transactional(readOnly = true)
	public ReviewContent read(String type, UUID id) {
		UUID conversation = facts(id).map(MessageFacts::conversationId).orElseThrow(() -> ApiException.notFound("messaging.message_not_found"));
		return ReviewContent.pointer(conversation);
	}

}
