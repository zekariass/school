package com.nemaz.school.people.application;

import java.util.UUID;

import com.nemaz.school.academic.ClassAccess;
import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.academic.DataAccessLevel;
import com.nemaz.school.academic.SqlFragment;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.common.security.SchoolGate;
import com.nemaz.school.guardians.GuardianAccess;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Who may see a student, and how much (the privacy split): FULL for holders of {@code school.students.manage}, the
 * student themself, teachers of one of the student's ACTIVE classes and the student's guardians (ACTIVE link without
 * custody restriction, M5); BASIC for anyone who can see the roster of a class the student is ACTIVE in (classmates,
 * campus administrators with {@code class.manage}; never a guardian of a classmate: guardians see classes, not
 * rosters); nobody else, which callers answer with 404 so that student ids cannot be probed.
 */
@Component
class StudentAccess {

	private final JdbcClient jdbc;

	private final SchoolGate gate;

	private final ClassMemberships classes;

	private final ClassAccess classAccess;

	private final GuardianAccess guardians;

	StudentAccess(JdbcClient jdbc, SchoolGate gate, ClassMemberships classes, ClassAccess classAccess,
			GuardianAccess guardians) {
		this.jdbc = jdbc;
		this.gate = gate;
		this.classes = classes;
		this.classAccess = classAccess;
		this.guardians = guardians;
	}

	/** Null when the student is unknown in the school or invisible to the caller. */
	DataAccessLevel level(CurrentUser user, UUID schoolId, UUID studentId) {
		boolean exists = this.jdbc.sql("select exists (select 1 from school_students where school_id = :s and student_id = :st)")
			.param("s", schoolId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			return null;
		}
		if (this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE) || user.userId().equals(studentId)
				|| this.classes.teachesStudent(user.userId(), studentId)
				|| this.guardians.isActiveGuardian(user.userId(), studentId)) {
			return DataAccessLevel.FULL;
		}
		// The roster predicate, not the view predicate: guardians may see a class but not the other children in it.
		SqlFragment visible = this.classAccess.rosterVisibleClasses(user, "c");
		boolean shares = this.jdbc.sql("""
				select exists (select 1 from class_students cs join classes c on c.id = cs.class_id
				where cs.student_id = :ca_student and cs.status = 'ACTIVE' and c.school_id = :ca_school and (""" + visible.sql() + "))")
			.param("ca_student", studentId)
			.param("ca_school", schoolId)
			.params(visible.params())
			.query(Boolean.class)
			.single();
		return shares ? DataAccessLevel.BASIC : null;
	}

	/** Managers of students and teachers of the student. */
	boolean mayAdminister(CurrentUser user, UUID schoolId, UUID studentId) {
		return this.gate.can(user, schoolId, Permissions.SCHOOL_STUDENTS_MANAGE)
				|| this.classes.teachesStudent(user.userId(), studentId);
	}

}
