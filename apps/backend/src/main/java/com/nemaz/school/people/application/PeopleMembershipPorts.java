package com.nemaz.school.people.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.academic.ClassMemberships;
import com.nemaz.school.common.persistence.Rows;
import com.nemaz.school.common.security.SchoolMemberships;
import com.nemaz.school.identity.AccountMemberships;
import com.nemaz.school.identity.ManagedAccountPolicy;
import com.nemaz.school.tenancy.SettingScope;
import com.nemaz.school.tenancy.SettingsService;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The membership ports this module implements: the shared kernel's {@link SchoolMemberships}, identity's
 * {@link AccountMemberships} and {@link ManagedAccountPolicy}. It must not depend on the media module (media's
 * avatar policy depends on {@link SchoolMemberships}); the identity port {@code LoginCodeRedeemer} is
 * {@link LoginCodeService}.
 */
@Component
@Transactional(readOnly = true)
class PeopleMembershipPorts implements SchoolMemberships, AccountMemberships, ManagedAccountPolicy {

	private final JdbcClient jdbc;

	private final TenantDirectory directory;

	private final SettingsService settings;

	private final ClassMemberships classes;

	PeopleMembershipPorts(JdbcClient jdbc, TenantDirectory directory, SettingsService settings, ClassMemberships classes) {
		this.jdbc = jdbc;
		this.directory = directory;
		this.settings = settings;
		this.classes = classes;
	}

	// --- SchoolMemberships ------------------------------------------------------------------

	@Override
	public boolean schoolExists(UUID schoolId) {
		return this.directory.school(schoolId).isPresent();
	}

	@Override
	public boolean isActiveMember(UUID userId, UUID schoolId) {
		return this.jdbc.sql("""
				select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')
				    or exists (select 1 from school_students where school_id = :s and student_id = :u and status = 'ACTIVE')""")
			.param("s", schoolId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean mayViewAvatar(UUID readerUserId, UUID subjectUserId) {
		// Schools in which the subject is an ACTIVE member, and whether as a student.
		record Membership(UUID schoolId, boolean student) {
		}
		List<Membership> subject = this.jdbc.sql("""
				select school_id, false as student from school_staff where user_id = :u and status = 'ACTIVE'
				union all select school_id, true from school_students where student_id = :u and status = 'ACTIVE'""")
			.param("u", subjectUserId)
			.query((rs, i) -> new Membership(Rows.uuid(rs, "school_id"), rs.getBoolean("student")))
			.list();
		for (Membership m : subject) {
			if (!m.student()) {
				if (isActiveMember(readerUserId, m.schoolId())) {
					return true;
				}
			}
			else if (isActiveStaff(readerUserId, m.schoolId())) {
				return true;
			}
		}
		return subject.stream().anyMatch(Membership::student) && this.classes.shareActiveClass(readerUserId, subjectUserId);
	}

	private boolean isActiveStaff(UUID userId, UUID schoolId) {
		return this.jdbc.sql("select exists (select 1 from school_staff where school_id = :s and user_id = :u and status = 'ACTIVE')")
			.param("s", schoolId)
			.param("u", userId)
			.query(Boolean.class)
			.single();
	}

	// --- AccountMemberships -----------------------------------------------------------------

	@Override
	public List<SchoolMembership> activeFor(UUID userId) {
		List<SchoolMembership> result = new ArrayList<>();
		this.jdbc.sql("""
				select school_id, id, staff_type, campus_id from school_staff where user_id = :u and status = 'ACTIVE' order by created_at, id""")
			.param("u", userId)
			.query((rs, i) -> {
				membership(result, rs.getString("staff_type").equals("TEACHER") ? MembershipType.TEACHER : MembershipType.STAFF,
						Rows.uuid(rs, "school_id"), Rows.uuid(rs, "id"), null, Rows.uuid(rs, "campus_id"));
				return null;
			})
			.list();
		this.jdbc.sql("select school_id, campus_id from school_students where student_id = :u and status = 'ACTIVE' order by created_at, id")
			.param("u", userId)
			.query((rs, i) -> {
				membership(result, MembershipType.STUDENT, Rows.uuid(rs, "school_id"), null, userId, Rows.uuid(rs, "campus_id"));
				return null;
			})
			.list();
		return result;
	}

	private void membership(List<SchoolMembership> into, MembershipType type, UUID schoolId, UUID staffId, UUID studentId,
			UUID campusId) {
		this.directory.school(schoolId)
			.ifPresent((s) -> into.add(new SchoolMembership(type, schoolId, s.slug(), s.name(), staffId, studentId, campusId)));
	}

	// --- ManagedAccountPolicy ---------------------------------------------------------------

	/** A managed student may use a password only if every school they are ACTIVE in allows it (and at least one exists). */
	@Override
	public boolean mayUsePassword(UUID userId) {
		List<UUID> schools = this.jdbc.sql("select school_id from school_students where student_id = :u and status = 'ACTIVE'")
			.param("u", userId)
			.query(UUID.class)
			.list();
		return !schools.isEmpty() && schools.stream()
			.allMatch((s) -> this.settings.getBoolean("students.password_login.enabled", SettingScope.school(s)));
	}

}
