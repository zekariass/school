package com.nemaz.school.safeguarding.application;

import java.util.UUID;

import com.nemaz.school.common.error.ApiException;
import com.nemaz.school.common.security.AccessControl;
import com.nemaz.school.common.security.AccessScope;
import com.nemaz.school.common.security.CurrentUser;
import com.nemaz.school.common.security.Permissions;
import com.nemaz.school.safeguarding.application.IncidentRows.IncidentRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may know that an incident exists (docs/Safeguarding_Rules.md S4.2). Everybody who may not read it is answered 404
 * {@code safeguarding.not_found}, the same answer as for an id that does not exist: school administrators, principals who are
 * not leads, moderators, platform administrators and support agents never learn that an incident is there. Reading needs
 * {@code school.safeguarding.read} at the incident's school (platform leads hold it platform-wide; a platform incident has no
 * school and is covered by platform grants only). Nobody reads or works an incident about themselves or their own child:
 * somebody who is an involved person of it, or the guardian of its concerned student, is answered 404 too.
 */
@Component
@Transactional(readOnly = true)
class IncidentAccess {

	private final AccessControl access;

	private final JdbcClient jdbc;

	IncidentAccess(AccessControl access, JdbcClient jdbc) {
		this.access = access;
		this.jdbc = jdbc;
	}

	static ApiException notFound() {
		return ApiException.notFound("safeguarding.not_found");
	}

	private static AccessScope scopeOf(UUID schoolId) {
		return (schoolId != null) ? AccessScope.school(schoolId) : AccessScope.platform();
	}

	boolean canRead(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_READ, scopeOf(schoolId));
	}

	boolean canManage(CurrentUser user, UUID schoolId) {
		return this.access.can(user, Permissions.SCHOOL_SAFEGUARDING_MANAGE, scopeOf(schoolId));
	}

	/** 404 unless the caller is a lead of the school (or a platform lead). */
	void requireReader(CurrentUser user, UUID schoolId) {
		if (!canRead(user, schoolId)) {
			throw notFound();
		}
	}

	IncidentRow find(UUID incidentId) {
		return this.jdbc.sql("select " + IncidentRows.COLUMNS + " from safeguarding_incidents i where i.id = :id")
			.param("id", incidentId)
			.query(IncidentRows::incident)
			.optional()
			.orElse(null);
	}

	/** The incident when the caller may read it, else 404. */
	IncidentRow readable(CurrentUser user, UUID incidentId) {
		IncidentRow row = find(incidentId);
		if (row == null || !canRead(user, row.schoolId()) || conflicted(user.userId(), row)) {
			throw notFound();
		}
		return row;
	}

	/** The incident when the caller may work it (read and manage), else 404. */
	IncidentRow manageable(CurrentUser user, UUID incidentId) {
		IncidentRow row = readable(user, incidentId);
		if (!canManage(user, row.schoolId())) {
			throw notFound();
		}
		return row;
	}

	/** An involved person, or the guardian of the concerned student, or the concerned student: never works the incident. */
	boolean conflicted(UUID userId, IncidentRow row) {
		if (row.involved().contains(userId) || userId.equals(row.concernedStudentId())) {
			return true;
		}
		if (row.concernedStudentId() == null) {
			return false;
		}
		return this.jdbc.sql("""
				select exists (select 1 from student_guardians where guardian_id = :g and student_id = :st
				               and status in ('PENDING', 'ACTIVE'))""")
			.param("g", userId)
			.param("st", row.concernedStudentId())
			.query(Boolean.class)
			.single();
	}

}
