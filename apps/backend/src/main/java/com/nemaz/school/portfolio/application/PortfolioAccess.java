package com.nemaz.school.portfolio.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipView;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianAccess.GuardianGrant;
import com.nemaz.school.guardians.GuardianPermission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access questions of portfolios, answered through the other modules' ports ({@link ClassAccess}, {@link GuardianAccess},
 * {@link SchoolGate}); who may see an item is {@link PortfolioAudience}. The 404-or-403 rule: a school, class, student or item the
 * caller cannot see is 404 (exactly like an unknown id), one the caller can see but may not use for this is 403.
 */
@Component
@Transactional(readOnly = true)
public class PortfolioAccess {

	private final ClassAccess classes;

	private final ClassMemberships memberships;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final PortfolioSettings settings;

	private final JdbcClient jdbc;

	PortfolioAccess(ClassAccess classes, ClassMemberships memberships, SchoolGate gate, SchoolPeople people,
			GuardianAccess guardians, PortfolioSettings settings, JdbcClient jdbc) {
		this.classes = classes;
		this.memberships = memberships;
		this.gate = gate;
		this.people = people;
		this.guardians = guardians;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	ClassAccess classes() {
		return this.classes;
	}

	ClassMemberships memberships() {
		return this.memberships;
	}

	SchoolPeople people() {
		return this.people;
	}

	GuardianAccess guardians() {
		return this.guardians;
	}

	PortfolioSettings settings() {
		return this.settings;
	}

	/** 404 unless the school is visible to the caller (member, administrator or a guardian of one of its students), then 403 when the feature is off. */
	void requireSchool(CurrentUser user, UUID schoolId) {
		if (!guardianOfSchool(user, schoolId)) {
			this.gate.requireVisible(user, schoolId);
		}
		requireFeature(schoolId);
	}

	void requireFeature(UUID schoolId) {
		if (!this.settings.featureEnabled(schoolId)) {
			throw ApiException.forbidden("portfolio.feature_disabled");
		}
	}

	/** True for an adult with an ACTIVE, unrestricted link (with academics) to an ACTIVE student of the school. */
	boolean guardianOfSchool(CurrentUser user, UUID schoolId) {
		return this.jdbc.sql("""
				select exists (select 1 from student_guardians g join school_students ss on ss.student_id = g.student_id
				  and ss.status = 'ACTIVE' and ss.school_id = :school
				  where g.guardian_id = :user and g.status = 'ACTIVE' and not g.custody_restricted and g.can_view_academics)""")
			.param("school", schoolId)
			.param("user", user.userId())
			.query(Boolean.class)
			.single();
	}

	boolean schoolActive(UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from schools where id = :id and status = 'ACTIVE')")
			.param("id", schoolId)
			.query(Boolean.class)
			.single();
	}

	/** A student creating (or changing) their own items: ACTIVE in an ACTIVE school with the setting on. */
	void requireStudentWriter(CurrentUser user, UUID schoolId) {
		requireSchool(user, schoolId);
		if (!this.people.isActiveStudent(schoolId, user.userId())) {
			throw ApiException.forbidden("portfolio.student_only");
		}
		if (!schoolActive(schoolId)) {
			throw ApiException.forbidden("portfolio.school_inactive");
		}
		if (!this.settings.studentCreate(schoolId)) {
			throw ApiException.forbidden("portfolio.student_create_disabled");
		}
	}

	/** The class if it belongs to the school and the caller may view it; 404 {@code class.not_found} otherwise. */
	ClassRef viewableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !this.classes.canView(user, classId)) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	Optional<TeacherRole> teacherRole(CurrentUser user, UUID classId) {
		return this.classes.membershipOf(user, classId).filter((m) -> m.kind() == Kind.TEACHER).map(ClassMembershipView::teacherRole);
	}

	/** May the user quick-capture in the class: ACTIVE LEAD, CO_TEACHER or SUBJECT_TEACHER, an ASSISTANT only while the setting is on. */
	boolean canCapture(CurrentUser user, ClassRef ref) {
		return teacherRole(user, ref.id())
			.map((role) -> role != TeacherRole.ASSISTANT || this.settings.assistantsCapture(ref.schoolId()))
			.orElse(false);
	}

	/** Whether the caller reviews anything in the class (a reviewing teacher, a manager, an oversight holder). */
	boolean reviewsClass(CurrentUser user, ClassRef ref) {
		Optional<TeacherRole> role = teacherRole(user, ref.id());
		if (role.isPresent() && role.get() != TeacherRole.ASSISTANT) {
			return true;
		}
		return this.classes.canManage(user, ref.id()) || this.gate.can(user, ref.schoolId(), com.nemaz.school.common.security.Permissions.SCHOOL_PORTFOLIOS_REVIEW);
	}

	/** Children the caller guards with an ACTIVE, unrestricted link and {@code can_view_academics}. */
	Set<UUID> guardedStudents(CurrentUser user) {
		return this.guardians.childrenOf(user.userId())
			.stream()
			.filter((g) -> g.allows(GuardianPermission.VIEW_ACADEMICS))
			.map(GuardianGrant::studentId)
			.collect(Collectors.toSet());
	}

	boolean isGuardianOf(CurrentUser user, UUID studentId) {
		return this.guardians.can(user.userId(), studentId, GuardianPermission.VIEW_ACADEMICS);
	}

}
