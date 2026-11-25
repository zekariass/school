package com.nemaz.school.guardians.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nemaz.school.identity.AccountMemberships;
import com.nemaz.school.tenancy.TenantDirectory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * GUARDIAN entries of {@code GET /me} memberships: one per school in which the user has at least one ACTIVE, not
 * custody-restricted link to an ACTIVE student. Clients derive the parent role and the child switcher from it.
 */
@Component
@Transactional(readOnly = true)
class GuardianMemberships implements AccountMemberships {

	private final JdbcClient jdbc;

	private final TenantDirectory directory;

	GuardianMemberships(JdbcClient jdbc, TenantDirectory directory) {
		this.jdbc = jdbc;
		this.directory = directory;
	}

	@Override
	public List<SchoolMembership> activeFor(UUID userId) {
		List<UUID> schools = this.jdbc.sql("""
				select ss.school_id from student_guardians sg
				join school_students ss on ss.student_id = sg.student_id and ss.status = 'ACTIVE'
				where sg.guardian_id = :u and sg.status = 'ACTIVE' and not sg.custody_restricted
				group by ss.school_id order by min(sg.created_at), ss.school_id""")
			.param("u", userId)
			.query(UUID.class)
			.list();
		List<SchoolMembership> result = new ArrayList<>();
		for (UUID schoolId : schools) {
			this.directory.school(schoolId)
				.ifPresent((s) -> result.add(new SchoolMembership(MembershipType.GUARDIAN, schoolId, s.slug(), s.name(),
						null, null, null)));
		}
		return result;
	}

}
