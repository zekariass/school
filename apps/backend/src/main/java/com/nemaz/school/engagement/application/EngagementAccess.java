package com.nemaz.school.engagement.application;

import java.util.Optional;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.tenancy.FeatureService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access questions of the engagement module, answered through the other modules' ports
 * ({@link ClassAccess}, {@link GuardianAccess}, {@link SchoolGate}); no rule is restated here. The 404-or-403 rule:
 * a class or student the caller cannot see is 404 (exactly like an unknown id), one the caller can see but may not use
 * for this is 403.
 */
@Component
@Transactional(readOnly = true)
class EngagementAccess {

	static final String FEATURE = "points";

	private final ClassAccess classes;

	private final ClassMemberships memberships;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final FeatureService features;

	private final JdbcClient jdbc;

	EngagementAccess(ClassAccess classes, ClassMemberships memberships, SchoolGate gate, SchoolPeople people,
			GuardianAccess guardians, FeatureService features, JdbcClient jdbc) {
		this.classes = classes;
		this.memberships = memberships;
		this.gate = gate;
		this.people = people;
		this.guardians = guardians;
		this.features = features;
		this.jdbc = jdbc;
	}

	ClassAccess classes() {
		return this.classes;
	}

	SchoolGate gate() {
		return this.gate;
	}

	GuardianAccess guardians() {
		return this.guardians;
	}

	ClassMemberships memberships() {
		return this.memberships;
	}

	SchoolPeople people() {
		return this.people;
	}

	/** The class if it belongs to the school and the caller may view it; 404 {@code class.not_found} otherwise. */
	ClassRef viewableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.classes.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	/** Like {@link #viewableClass}, then 403 {@code class.access_denied} unless the caller teaches or manages the class. */
	ClassRef teachableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewableClass(user, schoolId, classId);
		if (!this.classes.canTeach(user, classId)) {
			throw ApiException.forbidden("class.access_denied");
		}
		return ref;
	}

	/** May the user change the class's own categories: its LEAD or CO_TEACHER, a class manager, a points manager. */
	boolean mayEditClassCategories(CurrentUser user, ClassRef ref) {
		if (this.gate.can(user, ref.schoolId(), Permissions.SCHOOL_POINTS_MANAGE) || this.classes.canLead(user, ref.id())) {
			return true;
		}
		return this.classes.membershipOf(user, ref.id())
			.filter((m) -> m.kind() == Kind.TEACHER && m.teacherRole() == TeacherRole.CO_TEACHER)
			.isPresent();
	}

	boolean pointsManager(CurrentUser user, UUID schoolId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
	}

	boolean pointsReader(CurrentUser user, UUID schoolId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_POINTS_READ)
				|| this.gate.can(user, schoolId, Permissions.SCHOOL_POINTS_MANAGE);
	}

	boolean activeTeacher(CurrentUser user, UUID schoolId) {
		return this.people.isActiveTeacher(schoolId, user.userId());
	}

	/** 403 {@code points.feature_disabled} when the school's plan or switches turned points off. */
	void requireFeature(UUID schoolId) {
		if (!this.features.isEnabled(FEATURE, schoolId)) {
			throw ApiException.forbidden("points.feature_disabled");
		}
	}

	/** The school's current academic year, if it has one. */
	Optional<UUID> currentYear(UUID schoolId) {
		return this.jdbc.sql("select id from academic_years where school_id = :s and is_current")
			.param("s", schoolId)
			.query(UUID.class)
			.optional();
	}

	boolean activeStudentOfSchool(UUID schoolId, UUID studentId) {
		return this.people.isActiveStudent(schoolId, studentId);
	}

	/** Does the school know the student at all (any status)? */
	boolean knownStudent(UUID schoolId, UUID studentId) {
		return this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st)")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	/**
	 * May the caller see that the student exists (so that a missing permission is 403, not 404)? Self, guardians,
	 * teachers of the student, student and points managers, and those who can see the roster of one of the student's
	 * classes (classmates).
	 */
	boolean canSeeStudent(CurrentUser user, UUID schoolId, UUID studentId) {
		if (!knownStudent(schoolId, studentId)) {
			return false;
		}
		if (user.userId().equals(studentId) || this.guardians.isActiveGuardian(user.userId(), studentId)
				|| this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE) || pointsReader(user, schoolId)
				|| this.memberships.teachesStudent(user.userId(), studentId)) {
			return true;
		}
		SqlFragment visible = this.classes.rosterVisibleClasses(user, "c");
		return this.jdbc.sql("""
				select exists (select 1 from class_students cs join classes c on c.id = cs.class_id
				where cs.student_id = :ca_student and cs.status = 'ACTIVE' and c.school_id = :ca_school and (""" + visible.sql() + "))")
			.param("ca_student", studentId)
			.param("ca_school", schoolId)
			.params(visible.params())
			.query(Boolean.class)
			.single();
	}

	/** The error for a student the caller may not use: 403 when they can see the student, else 404 {@code student.not_found}. */
	ApiException studentDenied(CurrentUser user, UUID schoolId, UUID studentId) {
		if (!this.gate.canSee(user, schoolId) && !this.guardians.isActiveGuardian(user.userId(), studentId)) {
			return ApiException.notFound("school.not_found");
		}
		return canSeeStudent(user, schoolId, studentId) ? ApiException.forbidden("points.access_denied")
				: ApiException.notFound("student.not_found");
	}

}
