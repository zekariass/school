package com.nemaz.school.messaging.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.ConversationType;
import com.nemaz.school.messaging.domain.DirectKind;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.domain.MemberRole;

/** Row types of the messaging tables and the column lists (aliases {@code c} and {@code m}) that fill them. */
public final class MessagingRows {

	public static final String CONVERSATION_COLUMNS = """
			c.id as c_id, c.conversation_type as c_type, c.school_id as c_school, c.class_id as c_class,
			c.context_student_id as c_student, c.direct_kind as c_dkind, c.group_kind as c_gkind, c.title as c_title,
			c.posting_policy as c_posting, c.status as c_status, c.safeguarded as c_safeguarded,
			c.admin_visible as c_admin_visible, c.created_by as c_created_by, c.last_message_at as c_last,
			c.activity_at as c_activity, c.created_at as c_created""";

	public static final String MEMBER_COLUMNS = """
			m.id as m_id, m.conversation_id as m_conv, m.user_id as m_user, m.member_role as m_role,
			m.acting_as as m_acting, m.on_behalf_of_student_id as m_student, m.muted_until as m_muted,
			m.last_read_seq as m_seq, m.last_read_message_id as m_read_id, m.last_read_at as m_read_at""";

	private MessagingRows() {
	}

	public record ConversationRow(UUID id, ConversationType type, UUID schoolId, UUID classId, UUID contextStudentId,
			DirectKind directKind, GroupKind groupKind, String title, String postingPolicy, String status,
			boolean safeguarded, boolean adminVisible, UUID createdBy, Instant lastMessageAt, Instant activityAt,
			Instant createdAt) {

		public boolean direct() {
			return this.type == ConversationType.DIRECT;
		}

		public boolean active() {
			return "ACTIVE".equals(this.status);
		}

		public boolean moderatorsOnly() {
			return "MODERATORS_ONLY".equals(this.postingPolicy);
		}

	}

	public record MemberRow(UUID id, UUID conversationId, UUID userId, MemberRole role, ActingAs actingAs,
			UUID onBehalfOfStudentId, Instant mutedUntil, long lastReadSeq, UUID lastReadMessageId, Instant lastReadAt) {

		public boolean observer() {
			return this.role == MemberRole.OBSERVER;
		}

	}

	public static ConversationRow conversation(ResultSet rs) throws SQLException {
		String direct = rs.getString("c_dkind");
		String group = rs.getString("c_gkind");
		return new ConversationRow(Rows.uuid(rs, "c_id"), ConversationType.valueOf(rs.getString("c_type")),
				Rows.uuid(rs, "c_school"), Rows.uuid(rs, "c_class"), Rows.uuid(rs, "c_student"),
				(direct != null) ? DirectKind.valueOf(direct) : null, (group != null) ? GroupKind.valueOf(group) : null,
				rs.getString("c_title"), rs.getString("c_posting"), rs.getString("c_status"),
				rs.getBoolean("c_safeguarded"), rs.getBoolean("c_admin_visible"), Rows.uuid(rs, "c_created_by"),
				Rows.instant(rs, "c_last"), Rows.instant(rs, "c_activity"), Rows.instant(rs, "c_created"));
	}

	public static MemberRow member(ResultSet rs) throws SQLException {
		return new MemberRow(Rows.uuid(rs, "m_id"), Rows.uuid(rs, "m_conv"), Rows.uuid(rs, "m_user"),
				MemberRole.valueOf(rs.getString("m_role")), ActingAs.valueOf(rs.getString("m_acting")),
				Rows.uuid(rs, "m_student"), Rows.instant(rs, "m_muted"), rs.getLong("m_seq"),
				Rows.uuid(rs, "m_read_id"), Rows.instant(rs, "m_read_at"));
	}

}
