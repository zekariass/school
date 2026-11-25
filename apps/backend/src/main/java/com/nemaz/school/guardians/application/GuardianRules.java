package com.nemaz.school.guardians.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who may act on the guardians of a student S of school X (deny by default).
 * <ul>
 * <li><b>Invite</b> (SMS/email invitation, printed parent code, revoke an invitation): {@code school.guardians.manage}
 * covering X, or, while {@code guardians.teachers_can_invite} is on, an ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER (not
 * ASSISTANT) of a class of X in which S is ACTIVE.</li>
 * <li><b>Verify</b> (approve or reject a PENDING link): {@code school.guardians.manage}, or, while
 * {@code guardians.teachers_can_verify} is on, the ACTIVE LEAD teacher of a class of X in which S is ACTIVE.</li>
 * <li><b>Read links</b> (names, relationship, status): {@code school.guardians.manage} or any ACTIVE teacher of S in X;
 * restriction notes, contact details and decision notes only with {@code school.guardians.manage}.</li>
 * <li><b>Manage</b> (flags, custody restriction, primary, revoke, links created by the school, paper consent):
 * {@code school.guardians.manage} only.</li>
 * </ul>
 * S must be an ACTIVE student of X. 404 for callers who cannot see the school or the student (strangers, students,
 * teachers of other classes), 403 for those who can see the student but may not do this.
 */
@Component
class GuardianRules {

	static final String TEACHERS_CAN_INVITE = "guardians.teachers_can_invite";

	static final String TEACHERS_CAN_VERIFY = "guardians.teachers_can_verify";

	static final String AUTO_VERIFY = "guardians.auto_verify_invite";

	static final List<String> INVITING_ROLES = List.of("LEAD", "CO_TEACHER", "SUBJECT_TEACHER");

	static final List<String> ANY_ROLE = List.of("LEAD", "CO_TEACHER", "SUBJECT_TEACHER", "ASSISTANT");

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	private final SettingsService settings;

	GuardianRules(JdbcClient jdbc, SchoolGate gate, SettingsService settings) {
		this.jdbc = jdbc;
		this.gate = gate;
		this.settings = settings;
	}

	boolean manages(CurrentUser user, UUID schoolId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_GUARDIANS_MANAGE);
	}

	boolean mayInvite(CurrentUser user, UUID schoolId, UUID studentId) {
		return manages(user, schoolId) || (setting(TEACHERS_CAN_INVITE, schoolId)
				&& teaches(user.userId(), schoolId, studentId, INVITING_ROLES));
	}

	boolean mayVerify(CurrentUser user, UUID schoolId, UUID studentId) {
		return manages(user, schoolId)
				|| (setting(TEACHERS_CAN_VERIFY, schoolId) && teaches(user.userId(), schoolId, studentId, List.of("LEAD")));
	}

	boolean mayReadLinks(CurrentUser user, UUID schoolId, UUID studentId) {
		return manages(user, schoolId) || teaches(user.userId(), schoolId, studentId, ANY_ROLE);
	}

	/** An ACTIVE inviting teacher of the class (not ASSISTANT), while teachers may invite. */
	boolean mayInviteForClass(CurrentUser user, UUID schoolId, UUID classId) {
		if (manages(user, schoolId)) {
			return true;
		}
		return setting(TEACHERS_CAN_INVITE, schoolId) && this.jdbc.sql("""
				select exists (select 1 from class_teachers where class_id = :c and school_id = :s and teacher_id = :u
				               and status = 'ACTIVE' and role in (:roles))""")
			.param("c", classId)
			.param("s", schoolId)
			.param("u", user.userId())
			.param("roles", INVITING_ROLES)
			.query(Boolean.class)
			.single();
	}

	/** ACTIVE LEAD teacher of at least one class of the school while teachers may verify (for the queue). */
	boolean isVerifyingTeacher(CurrentUser user, UUID schoolId) {
		return setting(TEACHERS_CAN_VERIFY, schoolId) && this.jdbc.sql("""
				select exists (select 1 from class_teachers ct join classes c on c.id = ct.class_id
				               where ct.teacher_id = :u and ct.school_id = :s and ct.status = 'ACTIVE' and ct.role = 'LEAD')""")
			.param("u", user.userId())
			.param("s", schoolId)
			.query(Boolean.class)
			.single();
	}

	boolean autoVerifies(UUID schoolId) {
		return setting(AUTO_VERIFY, schoolId);
	}

	/**
	 * The 404-or-403 rule for an action on student S of school X that the caller may ({@code allowed}) or may not
	 * perform: allowed callers still get 404 {@code student.not_found} unless S is an ACTIVE student of X; others get
	 * 404 unless they can see S (they teach S), then 403 {@code guardian.access_denied}.
	 */
	void require(CurrentUser user, UUID schoolId, UUID studentId, boolean allowed) {
		if (allowed) {
			if (!activeStudent(schoolId, studentId)) {
				throw ApiException.notFound("student.not_found");
			}
			return;
		}
		this.gate.requireVisible(user, schoolId);
		if (!activeStudent(schoolId, studentId) || !teaches(user.userId(), schoolId, studentId, ANY_ROLE)) {
			throw ApiException.notFound("student.not_found");
		}
		throw ApiException.forbidden("guardian.access_denied");
	}

	/** 404 {@code school.not_found} unless the caller can see the school. */
	void requireVisible(CurrentUser user, UUID schoolId) {
		this.gate.requireVisible(user, schoolId);
	}

	boolean activeStudent(UUID schoolId, UUID studentId) {
		return this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	/** The user is an ACTIVE teacher (one of {@code roles}) of a class of the school in which the student is ACTIVE. */
	boolean teaches(UUID userId, UUID schoolId, UUID studentId, List<String> roles) {
		return this.jdbc.sql("""
				select exists (select 1 from class_teachers ct
				               join class_students cs on cs.class_id = ct.class_id and cs.status = 'ACTIVE'
				               where ct.teacher_id = :u and ct.status = 'ACTIVE' and ct.role in (:roles)
				                 and ct.school_id = :s and cs.student_id = :st)""")
			.param("u", userId)
			.param("roles", roles)
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	private boolean setting(String key, UUID schoolId) {
		return this.settings.getBoolean(key, SettingScope.school(schoolId));
	}

}
