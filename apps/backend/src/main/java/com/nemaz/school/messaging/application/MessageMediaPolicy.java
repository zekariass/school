package com.nemaz.school.messaging.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.media.ContextType;
import com.nemaz.school.media.MediaContext;
import com.nemaz.school.media.MediaInfo;
import com.nemaz.school.media.MediaPurpose;
import com.nemaz.school.media.MediaPurposePolicy;
import com.nemaz.school.messaging.application.MessagingPolicy.Access;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.infrastructure.Eligibility;
import com.nemaz.school.messaging.infrastructure.MessagingRows.ConversationRow;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may upload and read media of purpose MESSAGE (R-30: no ACL, access derives from the owning message; rule book R8.4).
 * <ul>
 * <li><b>Create</b> (an upload into a conversation, before the message exists): a member who may post there now (an observer
 * never; a student only when {@code messaging.student.media_enabled}).</li>
 * <li><b>Read</b>: the image must be attached to a message that is SENT or EDITED (not deleted, not hidden), and the caller must
 * be a <em>current</em> member or observer of the conversation, i.e. pass the live eligibility of R1, and not have blocked the
 * sender (unless an observer). Reviewers read the images of conversations they may review, which is always audited. The sender
 * is not special once the message is attached: a person who lost access to the conversation loses the picture they sent too.</li>
 * <li>The images are served only as re-encoded variants (the media module never signs an image original).</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
class MessageMediaPolicy implements MediaPurposePolicy {

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final ModerationService moderation;

	private final SchoolGate gate;

	private final MessagingSettings settings;

	MessageMediaPolicy(JdbcClient jdbc, @Lazy MessagingPolicy policy, @Lazy ModerationService moderation, SchoolGate gate,
			MessagingSettings settings) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.moderation = moderation;
		this.gate = gate;
		this.settings = settings;
	}

	@Override
	public MediaPurpose purpose() {
		return MediaPurpose.MESSAGE;
	}

	@Override
	public boolean canCreate(CurrentUser user, MediaContext context) {
		if (context.type() != ContextType.CONVERSATION) {
			return false;
		}
		Access access = this.policy.find(user.userId(), context.id(), false).orElse(null);
		if (access == null) {
			return false;
		}
		if (this.policy.postRefusal(access) != null) {
			return false;
		}
		return access.member().actingAs() != ActingAs.STUDENT || this.settings.studentMedia(access.conversation().schoolId());
	}

	@Override
	public UUID schoolOf(MediaContext context) {
		return this.policy.conversation(context.id()).map(ConversationRow::schoolId).orElse(null);
	}

	private record Attached(UUID messageId, UUID conversationId, String status, UUID senderId, UUID schoolId) {
	}

	private Attached attached(UUID mediaId) {
		return this.jdbc.sql("""
				select m.id, m.conversation_id, m.status, m.sender_id, m.school_id from message_attachments a
				join messages m on m.id = a.message_id where a.media_id = :media""")
			.param("media", mediaId)
			.query((rs, i) -> new Attached(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
					rs.getString("status"), rs.getObject("sender_id", UUID.class), rs.getObject("school_id", UUID.class)))
			.optional()
			.orElse(null);
	}

	@Override
	public boolean ownerAlwaysReads(MediaInfo media) {
		return attached(media.id()) == null;
	}

	@Override
	public boolean canRead(CurrentUser user, MediaInfo media) {
		Attached a = attached(media.id());
		if (a == null || !List.of("SENT", "EDITED").contains(a.status())) {
			return false;
		}
		Access access = this.policy.find(user.userId(), a.conversationId(), false).orElse(null);
		if (access != null) {
			if (access.member().observer()) {
				return true;
			}
			boolean blocked = this.jdbc.sql("select exists (select 1 from user_blocks where blocker_id = :me and blocked_id = :sender)")
				.param("me", user.userId())
				.param("sender", a.senderId())
				.query(Boolean.class)
				.single();
			return !blocked;
		}
		if (!this.gate.can(user, a.schoolId(), Permissions.SCHOOL_MESSAGING_REVIEW)) {
			return false;
		}
		ConversationRow conv = this.policy.conversation(a.conversationId()).orElse(null);
		return conv != null && this.moderation.reviewable(conv);
	}

	@Override
	public boolean canManage(CurrentUser user, MediaInfo media) {
		return media.ownerId().equals(user.userId()) && attached(media.id()) == null;
	}

	@Override
	public boolean auditReads() {
		return true;
	}

}
