package com.nemaz.school.messaging.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.messaging.application.MessagingModels.GuardianLabel;
import com.nemaz.school.messaging.application.MessagingModels.ParticipantView;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.MemberRole;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is shown how (rule book R2, R6, R12). One rule, used by every payload:
 * <ul>
 * <li>teachers, staff and students are named (everybody in the conversation already shares it with them);</li>
 * <li>a <b>guardian</b> is named only to themself. To others they are a label: the child's teachers see the relationship and the
 * child's first name ("Abebe's mother"), a student sees the relationship of their own guardians, other guardians see neither;</li>
 * <li>nothing else about a person (no date of birth, gender, username, phone, e-mail, avatar) is ever part of the view.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class ParticipantViews {

	private final JdbcClient jdbc;

	ParticipantViews(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** How the viewer relates to the people they look at. */
	public record Viewer(UUID userId, ActingAs actingAs) {
	}

	public Viewer viewerOf(UUID userId, ActingAs actingAs) {
		return new Viewer(userId, actingAs);
	}

	/** The views of the given members of one conversation (members who left are included). */
	public Map<UUID, ParticipantView> forMembers(Viewer viewer, Collection<UUID> memberIds) {
		Map<UUID, ParticipantView> views = new LinkedHashMap<>();
		if (memberIds.isEmpty()) {
			return views;
		}
		this.jdbc.sql("""
				select m.id, m.user_id, m.member_role, m.acting_as, u.display_name, sg.relationship_type, sx.given_name as student_given
				from conversation_members m
				join conversations c on c.id = m.conversation_id
				join users u on u.id = m.user_id
				left join lateral (select g.relationship_type from student_guardians g
				                   where g.guardian_id = m.user_id
				                     and g.student_id = coalesce(m.on_behalf_of_student_id, c.context_student_id)
				                   order by (g.status = 'ACTIVE') desc, g.created_at desc limit 1) sg on true
				left join users sx on sx.id = coalesce(m.on_behalf_of_student_id, c.context_student_id)
				where m.id in (:ids)""")
			.param("ids", memberIds)
			.query((rs, i) -> {
				UUID memberId = Rows.uuid(rs, "id");
				UUID userId = Rows.uuid(rs, "user_id");
				ActingAs acting = ActingAs.valueOf(rs.getString("acting_as"));
				MemberRole role = MemberRole.valueOf(rs.getString("member_role"));
				boolean self = userId.equals(viewer.userId());
				String relationship = rs.getString("relationship_type");
				RelationshipType type = (relationship != null) ? RelationshipType.valueOf(relationship) : null;
				String studentGiven = rs.getString("student_given");
				if (acting != ActingAs.GUARDIAN) {
					views.put(memberId, new ParticipantView(memberId, acting, role, self, rs.getString("display_name"), null));
				}
				else if (self) {
					views.put(memberId, new ParticipantView(memberId, acting, role, true, rs.getString("display_name"),
							new GuardianLabel(type, studentGiven)));
				}
				else {
					GuardianLabel label = switch (viewer.actingAs()) {
						case TEACHER, STAFF -> new GuardianLabel(type, studentGiven);
						case STUDENT -> new GuardianLabel(type, null);
						case GUARDIAN -> new GuardianLabel(null, null);
					};
					views.put(memberId, new ParticipantView(memberId, acting, role, false, null, label));
				}
				return memberId;
			})
			.list();
		return views;
	}

	public ParticipantView forMember(Viewer viewer, UUID memberId) {
		return forMembers(viewer, java.util.List.of(memberId)).get(memberId);
	}

}
