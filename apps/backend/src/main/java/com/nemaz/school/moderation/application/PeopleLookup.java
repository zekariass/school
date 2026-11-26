package com.nemaz.school.moderation.application;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The few facts about people that moderation needs for routing, priority and conflicts: is this person a student, what role
 * do they have at the school ("a student", "a guardian", "a teacher"), do they run the school (administrator, moderator or
 * safeguarding lead), is one the guardian of the other. Read straight from the shared people and role tables (as the
 * messaging module reads role assignments): ids and enums only, never a name.
 */
@Component
@Transactional(readOnly = true)
class PeopleLookup {

	private final JdbcClient jdbc;

	PeopleLookup(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean isStudent(UUID userId) {
		return this.jdbc.sql("select exists (select 1 from student_profiles where user_id = :u)")
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	/** STUDENT, TEACHER, STAFF, GUARDIAN or OTHER: the role label a school moderator may see instead of a name. */
	String roleAt(UUID userId, UUID schoolId) {
		if (isStudent(userId)) {
			return "STUDENT";
		}
		if (schoolId != null) {
			String staff = this.jdbc.sql("select staff_type from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE' limit 1")
				.param("s", schoolId)
				.param("u", userId)
				.query(String.class)
				.optional()
				.orElse(null);
			if (staff != null) {
				return "TEACHER".equals(staff) ? "TEACHER" : "STAFF";
			}
		}
		boolean guardian = this.jdbc.sql("select exists (select 1 from student_guardians where guardian_id = :u and status = 'ACTIVE')")
			.param("u", userId)
			.query(Boolean.class)
			.single();
		return guardian ? "GUARDIAN" : "OTHER";
	}

	/**
	 * Runs the school or oversees moderation: an ACTIVE role with school.manage, school.moderation.manage or
	 * school.safeguarding.read whose scope covers the school (school, organization, country, platform), or administrator
	 * staff. A case about such a person skips the school queue (S2.3): the school's own moderators are not to judge them.
	 */
	boolean isOversight(UUID userId, UUID schoolId) {
		if (schoolId == null) {
			return false;
		}
		boolean byRole = this.jdbc.sql("""
				select exists (select 1 from role_assignments ra
				  join role_permissions rp on rp.role_code = ra.role_code
				  join schools s on s.id = :school
				  where ra.user_id = :u and ra.status = 'ACTIVE'
				    and rp.permission_code in ('school.manage', 'school.moderation.manage', 'school.safeguarding.read')
				    and (ra.scope_type = 'PLATFORM'
				      or (ra.scope_type in ('SCHOOL', 'CAMPUS') and ra.school_id = s.id)
				      or (ra.scope_type = 'ORGANIZATION' and ra.organization_id = s.organization_id)
				      or (ra.scope_type = 'COUNTRY' and ra.country_code = s.country_code)))""")
			.param("school", schoolId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
		if (byRole) {
			return true;
		}
		return this.jdbc.sql("""
				select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE'
				               and staff_type = 'ADMINISTRATOR')""")
			.param("s", schoolId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	/** True when {@code guardianId} has a pending or active link to the student {@code studentId}. */
	boolean isGuardianOf(UUID guardianId, UUID studentId) {
		if (studentId == null) {
			return false;
		}
		return this.jdbc.sql("""
				select exists (select 1 from student_guardians where guardian_id = :g and student_id = :st
				               and status in ('PENDING', 'ACTIVE'))""")
			.param("g", guardianId)
			.param("st", studentId)
			.query(Boolean.class)
			.single();
	}

}
