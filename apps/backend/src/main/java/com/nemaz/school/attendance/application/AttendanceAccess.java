package com.nemaz.school.attendance.application;

import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassAccess.ClassRef;
import com.nemaz.school.academic.ClassMembershipView;
import com.nemaz.school.academic.ClassMembershipView.Kind;
import com.nemaz.school.academic.ClassMembershipView.TeacherRole;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.ClassMemberships.ClassTeacher;
import com.nemaz.school.academic.ClassMemberships.StudentClass;
import com.nemaz.school.academic.SchoolPeople;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;
import com.nemaz.school.guardians.GuardianPermission;
import com.nemaz.school.tenancy.FeatureService;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access questions of the attendance module, answered through the other modules' ports ({@link ClassAccess},
 * {@link GuardianAccess}, {@link SchoolGate}); no rule is restated here. The 404-or-403 rule: a class or student the
 * caller cannot see is 404 (exactly like an unknown id), one the caller can see but may not use for this is 403.
 * <ul>
 * <li><b>Take or correct a roll:</b> ACTIVE teachers of the class (assistants while {@code attendance.assistants_can_take}
 * is on), class managers, holders of {@code school.attendance.manage}.</li>
 * <li><b>Edit a locked roll:</b> the class's LEAD teacher, class managers and attendance managers, with a reason.</li>
 * <li><b>Read a roll:</b> the same people, plus holders of {@code school.attendance.read}.</li>
 * <li><b>Read absence-report notes (health data):</b> the LEAD, CO and SUBJECT teachers of the class and attendance
 * managers; assistants, other class managers and every other guardian do not.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
class AttendanceAccess {

	static final String FEATURE = "attendance";

	private final ClassAccess classes;

	private final ClassMemberships memberships;

	private final SchoolGate gate;

	private final SchoolPeople people;

	private final GuardianAccess guardians;

	private final FeatureService features;

	private final AttendanceSettings settings;

	private final JdbcClient jdbc;

	private final TenantDirectory directory;

	AttendanceAccess(ClassAccess classes, ClassMemberships memberships, SchoolGate gate, SchoolPeople people,
			GuardianAccess guardians, FeatureService features, AttendanceSettings settings, JdbcClient jdbc,
			TenantDirectory directory) {
		this.directory = directory;
		this.classes = classes;
		this.memberships = memberships;
		this.gate = gate;
		this.people = people;
		this.guardians = guardians;
		this.features = features;
		this.settings = settings;
		this.jdbc = jdbc;
	}

	ClassAccess classes() {
		return this.classes;
	}

	ClassMemberships memberships() {
		return this.memberships;
	}

	SchoolGate gate() {
		return this.gate;
	}

	SchoolPeople people() {
		return this.people;
	}

	GuardianAccess guardians() {
		return this.guardians;
	}

	/** 403 {@code attendance.feature_disabled} when the school's plan or switches turned attendance off. */
	void requireFeature(UUID schoolId) {
		if (!this.features.isEnabled(FEATURE, schoolId)) {
			throw ApiException.forbidden("attendance.feature_disabled");
		}
	}

	/**
	 * The QR check-in endpoints (opening a code, a scan, a student's check-in) work only in a school that is ACTIVE
	 * (403 {@code attendance.school_not_active}); the other attendance endpoints keep working while a school is onboarding.
	 */
	void requireActiveSchool(UUID schoolId) {
		if (!this.directory.school(schoolId).filter(TenantDirectory.SchoolRef::isActive).isPresent()) {
			throw ApiException.forbidden("attendance.school_not_active");
		}
	}

	/** 403 {@code attendance.method_disabled} unless the school turned the QR method on (QR_ROTATING or QR_STATIC). */
	void requireMethod(UUID schoolId, String method) {
		if (!this.settings.methodEnabled(schoolId, method)) {
			throw ApiException.forbidden("attendance.method_disabled");
		}
	}

	boolean manager(CurrentUser user, UUID schoolId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_MANAGE);
	}

	/** Holder of {@code school.attendance.read} or {@code school.attendance.manage}. */
	boolean reader(CurrentUser user, UUID schoolId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_ATTENDANCE_READ) || manager(user, schoolId);
	}

	boolean activeTeacher(CurrentUser user, UUID schoolId) {
		return this.people.isActiveTeacher(schoolId, user.userId());
	}

	// --- classes ----------------------------------------------------------------------------

	/** The class if it belongs to the school and the caller may see it (member, class manager or attendance reader). */
	ClassRef viewableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = this.classes.find(classId).filter((c) -> c.schoolId().equals(schoolId)).orElse(null);
		if (ref == null || !(this.classes.canView(user, classId) || reader(user, schoolId))) {
			throw ApiException.notFound("class.not_found");
		}
		return ref;
	}

	/** Like {@link #viewableClass}, then 403 unless the caller may read the roll (teaches, manages, attendance reader). */
	ClassRef readableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewableClass(user, schoolId, classId);
		requireFeature(schoolId);
		if (!(this.classes.canTeach(user, classId) || reader(user, schoolId))) {
			throw ApiException.forbidden("class.access_denied");
		}
		return ref;
	}

	/**
	 * Like {@link #viewableClass}, then 403 unless the caller may take the roll: an ACTIVE teacher (an assistant only
	 * while the school allows it), a class manager or an attendance manager. 409 for an archived class.
	 */
	ClassRef takeableClass(CurrentUser user, UUID schoolId, UUID classId) {
		ClassRef ref = viewableClass(user, schoolId, classId);
		requireFeature(schoolId);
		boolean manages = manager(user, schoolId) || this.classes.canManage(user, classId);
		if (!manages) {
			if (!this.classes.canTeach(user, classId)) {
				throw ApiException.forbidden("class.access_denied");
			}
			boolean assistantOnly = this.classes.membershipOf(user, classId)
				.filter((m) -> m.kind() == Kind.TEACHER)
				.map(ClassMembershipView::teacherRole)
				.filter((role) -> role == TeacherRole.ASSISTANT)
				.isPresent();
			if (assistantOnly && !this.settings.assistantsCanTake(schoolId)) {
				throw ApiException.forbidden("attendance.assistants_not_allowed");
			}
		}
		if (ref.archived()) {
			throw ApiException.conflict("class.archived");
		}
		return ref;
	}

	/** May the caller edit a roll after it locked (with a reason)? The lead teacher, class managers, attendance managers. */
	boolean mayOverrideLock(CurrentUser user, ClassRef ref) {
		return manager(user, ref.schoolId()) || this.classes.canLead(user, ref.id());
	}

	/** May the caller read the notes of absence reports of students in this class? */
	boolean mayReadNotes(CurrentUser user, ClassRef ref) {
		if (manager(user, ref.schoolId())) {
			return true;
		}
		return this.classes.membershipOf(user, ref.id())
			.filter((m) -> m.kind() == Kind.TEACHER && m.teacherRole() != TeacherRole.ASSISTANT)
			.isPresent();
	}

	/**
	 * May the caller decide on an absence report of the student (and read its note)? An attendance manager, or a LEAD,
	 * CO or SUBJECT teacher of a class the student is ACTIVE in.
	 */
	boolean mayDecideFor(CurrentUser user, UUID schoolId, UUID studentId) {
		if (manager(user, schoolId)) {
			return true;
		}
		List<UUID> classIds = this.memberships.activeClassesOf(List.of(studentId))
			.getOrDefault(studentId, List.of())
			.stream()
			.map(StudentClass::classId)
			.toList();
		for (ClassTeacher teacher : this.memberships.activeTeachers(classIds)) {
			if (teacher.teacherId().equals(user.userId()) && teacher.role() != TeacherRole.ASSISTANT) {
				return true;
			}
		}
		return false;
	}

	// --- students ---------------------------------------------------------------------------

	boolean knownStudent(UUID schoolId, UUID studentId) {
		return this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st)")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

	boolean activeStudent(UUID schoolId, UUID studentId) {
		return this.people.isActiveStudent(schoolId, studentId);
	}

	/** Who is reading a student's attendance: staff (teachers, attendance readers) see notes, family does not. */
	enum Viewer {
		STAFF, FAMILY
	}

	/**
	 * Decides whether the caller may read the student's attendance history, and as whom. 404 when the caller has no
	 * business with the student, 403 when they can see the student but not their attendance.
	 */
	Viewer historyViewer(CurrentUser user, UUID schoolId, UUID studentId) {
		if (!this.gate.canSee(user, schoolId) && !this.guardians.isActiveGuardian(user.userId(), studentId)) {
			throw ApiException.notFound("school.not_found");
		}
		if (!knownStudent(schoolId, studentId)) {
			throw ApiException.notFound("student.not_found");
		}
		requireFeature(schoolId);
		if (reader(user, schoolId) || this.memberships.teachesStudent(user.userId(), studentId)) {
			return Viewer.STAFF;
		}
		if (user.userId().equals(studentId)
				|| this.guardians.can(user.userId(), studentId, GuardianPermission.VIEW_ACADEMICS)) {
			return Viewer.FAMILY;
		}
		throw canSeeStudent(user, schoolId, studentId) ? ApiException.forbidden("attendance.access_denied")
				: ApiException.notFound("student.not_found");
	}

	/** May the caller know the student exists: guardians, classmates and the like (so a missing permission is 403). */
	private boolean canSeeStudent(CurrentUser user, UUID schoolId, UUID studentId) {
		if (this.guardians.isActiveGuardian(user.userId(), studentId)
				|| this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE)) {
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

}
