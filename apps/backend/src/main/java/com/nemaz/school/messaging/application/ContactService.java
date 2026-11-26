package com.nemaz.school.messaging.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.guardians.RelationshipType;
import com.nemaz.school.messaging.application.MessagingModels.ContactView;
import com.nemaz.school.messaging.application.MessagingModels.GuardianLabel;
import com.nemaz.school.messaging.application.MessagingModels.MessagingCapabilitiesView;
import com.nemaz.school.messaging.application.MessagingModels.OfficeHoursView;
import com.nemaz.school.messaging.application.MessagingModels.ParticipantViewLabel;
import com.nemaz.school.messaging.domain.ActingAs;
import com.nemaz.school.messaging.domain.GroupKind;
import com.nemaz.school.messaging.domain.MessageText;
import com.nemaz.school.messaging.infrastructure.MessagingSettings;
import com.nemaz.school.messaging.infrastructure.MessagingSettings.SchoolPolicy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way to find somebody to start a conversation with (rule book R2, R4): the <b>contacts of one child</b>, never a
 * search over people. A guardian of the child gets the child's teachers (by name) or, when the school allows parent-parent
 * messaging, the guardians of the child's classmates (as "Parent" labels); a teacher of the child gets the child's messageable
 * guardians (as labels "Abebe's mother"). A student gets 403: students have no directory. Everybody else gets 403.
 */
@Service
@Transactional(readOnly = true)
public class ContactService {

	private final JdbcClient jdbc;

	private final MessagingPolicy policy;

	private final MessagingSettings settings;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final SchoolGate gate;

	ContactService(JdbcClient jdbc, MessagingPolicy policy, MessagingSettings settings, SchoolPeople people,
			GuardianAccess guardians, SchoolGate gate) {
		this.jdbc = jdbc;
		this.policy = policy;
		this.settings = settings;
		this.people = people;
		this.guardians = guardians;
		this.gate = gate;
	}

	public List<ContactView> contacts(CurrentUser user, UUID schoolId, UUID studentId, String audience) {
		this.policy.requireSchool(user, schoolId);
		if (this.people.isActiveStudent(schoolId, user.userId())) {
			throw MessagingPolicy.notAllowed();
		}
		if (studentId == null) {
			throw ApiException.badRequest("messaging.context_student_required");
		}
		if (!this.people.isActiveStudent(schoolId, studentId)) {
			throw MessagingPolicy.notAllowed();
		}
		SchoolPolicy school = this.settings.policy(schoolId);
		boolean guardian = this.guardians.can(user.userId(), studentId, GuardianPermission.MESSAGE_STAFF);
		if (guardian && "PARENTS".equals(audience)) {
			if (!school.parentParent()) {
				throw MessagingPolicy.notAllowed();
			}
			return parentsOfClassmates(user, schoolId, studentId);
		}
		if (guardian) {
			if (!school.parentTeacher()) {
				throw MessagingPolicy.notAllowed();
			}
			return teachersOf(user, schoolId, studentId);
		}
		if (this.people.isActiveTeacher(schoolId, user.userId()) && this.policy.teaches(schoolId, user.userId(), studentId)
				&& school.parentTeacher()) {
			return guardiansOf(user, schoolId, studentId);
		}
		throw MessagingPolicy.notAllowed();
	}

	private List<ContactView> teachersOf(CurrentUser user, UUID schoolId, UUID studentId) {
		record Row(UUID teacher, String name, String role, String className, String subject) {
		}
		Map<UUID, Row> best = new LinkedHashMap<>();
		this.jdbc.sql("""
				select ct.teacher_id, u.display_name, ct.role, c.name as class_name, s.name as subject_name
				from class_teachers ct
				join class_students cs on cs.class_id = ct.class_id and cs.status = 'ACTIVE' and cs.student_id = :st
				join classes c on c.id = ct.class_id
				join users u on u.id = ct.teacher_id and u.status = 'ACTIVE'
				join school_staff st on st.school_id = ct.school_id and st.user_id = ct.teacher_id and st.status = 'ACTIVE' and st.staff_type = 'TEACHER'
				left join subjects s on s.id = ct.subject_id
				where ct.school_id = :s and ct.status = 'ACTIVE' and ct.role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER')
				order by case ct.role when 'LEAD' then 0 when 'CO_TEACHER' then 1 else 2 end, u.display_name, ct.teacher_id""")
			.param("st", studentId)
			.param("s", schoolId)
			.query((rs, i) -> {
				best.putIfAbsent(Rows.uuid(rs, "teacher_id"), new Row(Rows.uuid(rs, "teacher_id"), rs.getString("display_name"),
						rs.getString("role"), rs.getString("class_name"), rs.getString("subject_name")));
				return 0;
			})
			.list();
		List<ContactView> result = new ArrayList<>();
		for (Row row : best.values()) {
			String key = "GT:" + user.userId() + ":" + row.teacher() + ":" + studentId;
			result.add(new ContactView(row.teacher(), new ParticipantViewLabel(ActingAs.TEACHER, row.name(), null, row.role(),
					row.className(), row.subject()), existing(schoolId, key)));
		}
		return result;
	}

	private List<ContactView> guardiansOf(CurrentUser user, UUID schoolId, UUID studentId) {
		List<ContactView> result = new ArrayList<>();
		this.jdbc.sql("""
				select g.guardian_id, g.relationship_type, sx.given_name from student_guardians g
				join users sx on sx.id = g.student_id
				join users gu on gu.id = g.guardian_id and gu.status = 'ACTIVE'
				where g.student_id = :st and g.status = 'ACTIVE' and not g.custody_restricted and g.can_message_staff
				order by g.is_primary desc, g.created_at, g.guardian_id""")
			.param("st", studentId)
			.query((rs, i) -> {
				UUID guardian = Rows.uuid(rs, "guardian_id");
				String key = "GT:" + guardian + ":" + user.userId() + ":" + studentId;
				result.add(new ContactView(guardian, new ParticipantViewLabel(ActingAs.GUARDIAN, null,
						new GuardianLabel(RelationshipType.valueOf(rs.getString("relationship_type")), rs.getString("given_name")),
						null, null, null), existing(schoolId, key)));
				return 0;
			})
			.list();
		return result;
	}

	private List<ContactView> parentsOfClassmates(CurrentUser user, UUID schoolId, UUID studentId) {
		List<ContactView> result = new ArrayList<>();
		this.jdbc.sql("""
				select distinct g.guardian_id from student_guardians g
				join school_students ss on ss.student_id = g.student_id and ss.school_id = :s and ss.status = 'ACTIVE'
				join class_students cb on cb.student_id = g.student_id and cb.status = 'ACTIVE'
				join class_students ca on ca.class_id = cb.class_id and ca.student_id = :st and ca.status = 'ACTIVE'
				join users gu on gu.id = g.guardian_id and gu.status = 'ACTIVE'
				where g.status = 'ACTIVE' and not g.custody_restricted and g.can_message_staff and g.guardian_id <> :me
				order by g.guardian_id""")
			.param("s", schoolId)
			.param("st", studentId)
			.param("me", user.userId())
			.query((rs, i) -> {
				UUID other = Rows.uuid(rs, "guardian_id");
				String a = user.userId().toString();
				String b = other.toString();
				String key = "GG:" + ((a.compareTo(b) < 0) ? a + ":" + b : b + ":" + a);
				result.add(new ContactView(other, new ParticipantViewLabel(ActingAs.GUARDIAN, null, new GuardianLabel(null, null),
						null, null, null), existing(schoolId, key)));
				return 0;
			})
			.list();
		return result;
	}

	private UUID existing(UUID schoolId, String key) {
		return this.jdbc.sql("select id from conversations where school_id = :s and direct_key = :k")
			.param("s", schoolId)
			.param("k", key)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	/** What the caller may do in the school's messaging (for the clients' menus; the API stays the authority). */
	public MessagingCapabilitiesView capabilities(CurrentUser user, UUID schoolId) {
		this.policy.requireSchool(user, schoolId);
		SchoolPolicy school = this.settings.policy(schoolId);
		boolean student = this.people.isActiveStudent(schoolId, user.userId());
		boolean teacher = this.people.isActiveTeacher(schoolId, user.userId());
		boolean guardian = !this.guardians.childrenOf(user.userId()).isEmpty();
		boolean moderate = this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_MODERATE);
		boolean review = this.gate.can(user, schoolId, Permissions.SCHOOL_MESSAGING_REVIEW);
		List<GroupKind> groups = new ArrayList<>();
		if (!student) {
			for (GroupKind kind : GroupKind.values()) {
				if (kind.classBound()) {
					continue;
				}
				if (this.policy.findGroupBasis(user, schoolId, kind, null) != null) {
					groups.add(kind);
				}
			}
			if (teacher || moderate) {
				groups.add(GroupKind.CLASS_STUDENTS);
				groups.add(GroupKind.CLASS_PARENTS);
				groups.add(GroupKind.STUDENT_GROUP);
			}
		}
		OfficeHoursView hours = this.settings.officeHours(schoolId)
			.map((h) -> new OfficeHoursView(h.days(), h.start().toString(), h.end().toString(), this.settings.zone(schoolId).getId()))
			.orElse(null);
		return new MessagingCapabilitiesView(this.settings.featureEnabled(schoolId),
				!student && school.parentTeacher() && (teacher || guardian), student && school.studentDirect(),
				groups, student && this.settings.studentsMayProposeGroups(schoolId), moderate, review,
				this.settings.editWindowMinutes(schoolId), MessageText.MAX_LENGTH, this.settings.studentMedia(schoolId),
				this.settings.translation(schoolId), hours, this.settings.zone(schoolId).getId(), teacher && school.teacherDirect());
	}

}
